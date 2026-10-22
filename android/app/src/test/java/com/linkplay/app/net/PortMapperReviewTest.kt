package com.linkplay.app.net

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread

/**
 * Adversarial review of PortMapper: routers that misbehave the way real ones do,
 * and close() / renewal races. Fakes are separate from PortMapperTest's on purpose.
 */
private class RvGateway(bind: InetAddress) : Closeable {
    data class Req(val proto: String, val lifetime: Long, val internalPort: Int, val suggestedPort: Int, val nonce: List<Byte>?)

    @Volatile var silent = false
    @Volatile var pcp = true
    @Volatile var pmp = true
    /** How a router without PCP rejects our version-2 request: "op81", "op80" or "none" (silence). */
    @Volatile var v2Reject = "op81"
    /** A PCP-only router: answers a version-0 (NAT-PMP) request with a PCP-format UNSUPP_VERSION. */
    @Volatile var v0AsPcp = false
    /** Non-zero: PCP answers with this result code in a bare 24-byte header. */
    @Volatile var pcpHeaderError = 0
    @Volatile var badNonce = false
    @Volatile var delayMs = 0L
    @Volatile var external: InetAddress = InetAddress.getByName("203.0.113.9")
    @Volatile var grantS = 7200L
    /** Non-zero: the external port the router hands out whatever was suggested. */
    @Volatile var assignPort = 0

    private val socket = DatagramSocket(InetSocketAddress(bind, 0))
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<Req>()

    init { thread(isDaemon = true, name = "rv-gateway") { loop() } }

    private fun loop() {
        val buf = ByteArray(1500)
        while (true) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (e: IOException) { return }
            val r = buf.copyOf(p.length)
            if (r.size < 2) continue
            record(r)
            if (silent) continue
            val reply = when (r[0].toInt()) {
                2 -> when {
                    pcp && pcpHeaderError != 0 -> ByteArray(24).also { it[0] = 2; it[1] = 0x81.toByte(); it[3] = pcpHeaderError.toByte() }
                    pcp -> pcpReply(r)
                    v2Reject == "op81" -> byteArrayOf(0, 0x81.toByte(), 0, 1, 0, 0, 0, 7)
                    v2Reject == "op80" -> byteArrayOf(0, 0x80.toByte(), 0, 1, 0, 0, 0, 7)
                    else -> null
                }
                0 -> when {
                    pmp -> pmpReply(r)
                    v0AsPcp -> ByteArray(24).also { it[0] = 2; it[1] = 0x80.toByte(); it[3] = 1 }
                    else -> null
                }
                else -> null
            } ?: continue
            if (delayMs > 0) Thread.sleep(delayMs)
            try { socket.send(DatagramPacket(reply, reply.size, p.socketAddress)) } catch (e: IOException) { return }
        }
    }

    private fun record(r: ByteArray) {
        when {
            r[0].toInt() == 2 && r.size >= 60 -> requests += Req("pcp", u32(r, 4), u16(r, 40), u16(r, 42), r.copyOfRange(24, 36).toList())
            r[0].toInt() == 0 && r[1].toInt() == 0 -> requests += Req("pmp-addr", 0, 0, 0, null)
            r[0].toInt() == 0 && r[1].toInt() == 1 && r.size >= 12 -> requests += Req("pmp", u32(r, 8), u16(r, 4), u16(r, 6), null)
        }
    }

    private fun pcpReply(r: ByteArray): ByteArray? {
        if (r.size < 60 || r[1].toInt() != 1) return null
        val lifetime = u32(r, 4)
        val nonce = r.copyOfRange(24, 36).also { if (badNonce) it[0] = (it[0] + 1).toByte() }
        val internal = u16(r, 40)
        val suggested = u16(r, 42)
        val extPort = if (assignPort != 0) assignPort else if (suggested != 0) suggested else 50000
        return ByteBuffer.allocate(60).put(2).put(0x81.toByte()).put(0).put(0)
            .putInt(minOf(lifetime, grantS).toInt()).putInt(1234).put(ByteArray(12))
            .put(nonce).put(17).put(ByteArray(3)).putShort(internal.toShort()).putShort(extPort.toShort())
            .put(ByteArray(10) + byteArrayOf(-1, -1) + external.address).array()
    }

    private fun pmpReply(r: ByteArray): ByteArray? = when (r[1].toInt()) {
        0 -> ByteBuffer.allocate(12).put(0).put(0x80.toByte()).putShort(0).putInt(1234).put(external.address).array()
        1 -> {
            val internal = u16(r, 4)
            val suggested = u16(r, 6)
            val lifetime = u32(r, 8)
            ByteBuffer.allocate(16).put(0).put(0x81.toByte()).putShort(0).putInt(1234).putShort(internal.toShort())
                .putShort((if (lifetime == 0L) 0 else if (assignPort != 0) assignPort else if (suggested != 0) suggested else internal).toShort())
                .putInt(minOf(lifetime, grantS).toInt()).array()
        }
        else -> null
    }

    override fun close() = socket.close()

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
}

/**
 * An IGD whose description comes from [description] (given the HTTP port). Every SOAP
 * request is parsed with a namespace-aware XML parser, so a malformed envelope shows.
 */
private class RvIgd(
    private val description: (Int) -> String,
    private val locationLine: (String) -> String = { "LOCATION: $it" },
) : Closeable {
    data class Call(val path: String, val action: String, val soapAction: String?, val contentType: String?,
                    val argNames: List<String>, val args: Map<String, String>, val envelopeError: String?)

    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var addDelayMs = 0L
    /** GetExternalIPAddress answer per control path (default 203.0.113.7). */
    val externals = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Control paths where the daemon drops the connection without answering. */
    val broken = CopyOnWriteArraySet<String>()
    private val lo = InetAddress.getByName("127.0.0.1")
    private val http = HttpServer.create(InetSocketAddress(lo, 0), 0)
    private val ssdp = DatagramSocket(InetSocketAddress(lo, 0))
    val ssdpTarget = InetSocketAddress(lo, ssdp.localPort)
    val httpPort: Int get() = http.address.port

    fun calls(action: String) = calls.filter { it.action == action }

    init {
        http.executor = Executors.newCachedThreadPool { r -> Thread(r, "rv-igd-http").apply { isDaemon = true } }
        http.createContext("/") { ex ->
            if (ex.requestMethod == "GET") {
                val body = description(httpPort).toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                return@createContext
            }
            val path = ex.requestURI.path
            val raw = ex.requestBody.use { it.readBytes() }
            val soapAction = ex.requestHeaders.getFirst("SOAPAction")
            val (type, action) = soapAction.trim('"').split('#')
            var error: String? = null
            val names = ArrayList<String>()
            val args = LinkedHashMap<String, String>()
            try {
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(raw.inputStream())
                val env = doc.documentElement
                val soapNs = "http://schemas.xmlsoap.org/soap/envelope/"
                if (env.localName != "Envelope" || env.namespaceURI != soapNs) error = "root is {${env.namespaceURI}}${env.localName}"
                val body = env.getElementsByTagNameNS(soapNs, "Body").item(0) as org.w3c.dom.Element?
                val call = body?.childNodes?.let { kids -> (0 until kids.length).map { kids.item(it) }.firstOrNull { it is org.w3c.dom.Element } } as org.w3c.dom.Element?
                when {
                    call == null -> error = "no action element"
                    call.localName != action -> error = "action element ${call.localName} != SOAPAction $action"
                    call.namespaceURI != type -> error = "action namespace ${call.namespaceURI} != $type"
                    else -> {
                        val kids = call.childNodes
                        for (i in 0 until kids.length) {
                            val k = kids.item(i) as? org.w3c.dom.Element ?: continue
                            names += k.localName; args[k.localName] = k.textContent
                        }
                    }
                }
            } catch (e: Exception) { error = "unparseable: $e" }
            calls += Call(path, action, soapAction, ex.requestHeaders.getFirst("Content-Type"), names, args, error)
            if (path in broken) throw IOException("daemon drops the connection") // HttpServer closes it unanswered
            if (action == "AddPortMapping" && addDelayMs > 0) Thread.sleep(addDelayMs) // rule applied, answer late
            val inner = if (action == "GetExternalIPAddress")
                "<NewExternalIPAddress>${externals[path] ?: "203.0.113.7"}</NewExternalIPAddress>" else ""
            val reply = ("<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:${action}Response xmlns:u=\"$type\">" +
                "$inner</u:${action}Response></s:Body></s:Envelope>").toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        http.start()
        thread(isDaemon = true, name = "rv-ssdp") {
            val buf = ByteArray(1500)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try { ssdp.receive(p) } catch (e: IOException) { break }
                val text = String(buf, 0, p.length)
                if (!text.startsWith("M-SEARCH")) continue
                val st = text.lines().first { it.startsWith("ST:", true) }.substringAfter(':').trim()
                val reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: $st\r\nUSN: uuid:rv::$st\r\nEXT:\r\n" +
                    locationLine("http://127.0.0.1:$httpPort/desc/rootDesc.xml") + "\r\n\r\n").toByteArray()
                try { ssdp.send(DatagramPacket(reply, reply.size, p.socketAddress)) } catch (e: IOException) { break }
            }
        }
    }

    override fun close() {
        ssdp.close()
        http.stop(0)
    }
}

/** A nested IGD description with the given (service type suffix, controlURL) pairs. */
private fun igd(vararg services: Pair<String, String>, urlBase: String? = null): (Int) -> String = { _ ->
    """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">${urlBase?.let { "\n <URLBase>$it</URLBase>" } ?: ""}
 <device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
  <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
   <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
    <serviceList>
${services.joinToString("\n") { (t, c) -> "     <service><serviceType>urn:schemas-upnp-org:service:$t</serviceType><serviceId>urn:upnp-org:serviceId:x</serviceId><controlURL>$c</controlURL></service>" }}
    </serviceList>
   </device></deviceList>
  </device></deviceList>
 </device>
</root>"""
}

class PortMapperReviewTest {
    private val lo4 = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val logs = CopyOnWriteArrayList<String>()
    private val closers = ArrayList<Closeable>()
    private val port = 40123

    @After fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    private fun <T : Closeable> keep(c: T): T = c.also { closers += it }
    private fun silence(): InetSocketAddress = InetSocketAddress(lo4, keep(DatagramSocket(InetSocketAddress(lo4, 0))).localPort)
    private fun mapper(gateway: Inet4Address?, options: PortMapper.Options) = keep(PortMapper(port, gateway, options) { logs += it })
    private fun mapped(ip: String, p: Int = port) = Candidate(InetSocketAddress(InetAddress.getByName(ip), p), Candidate.Kind.MAPPED)
    private fun gw(configure: RvGateway.() -> Unit = {}) = keep(RvGateway(lo4)).apply(configure)
    private fun pcpOptions(g: RvGateway, leaseS: Int = 3600) = PortMapper.Options(serverPort = g.port, ssdpTarget = silence(), leaseS = leaseS)

    private fun waitFor(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; log: $logs")
            Thread.sleep(20)
        }
    }

    // ---- UPnP

    @Test fun upnpSoapEnvelopeIsWellFormedXml() {
        val igd = keep(RvIgd(igd("WANIPConnection:1" to "/ctl/IP")))
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget, description = "Ash & Amy <link> \"play\""))
        assertEquals(listOf(mapped("203.0.113.7")), m.map())
        m.close()
        assertTrue(m.awaitReleased(3000))
        for (c in igd.calls) {
            assertNull("${c.action}: ${c.envelopeError}", c.envelopeError)
            assertEquals("\"urn:schemas-upnp-org:service:WANIPConnection:1#${c.action}\"", c.soapAction)
            assertTrue(c.contentType, c.contentType!!.startsWith("text/xml"))
        }
        val add = igd.calls("AddPortMapping").single()
        // Argument order as in the service description (some routers parse positionally).
        assertEquals(listOf("NewRemoteHost", "NewExternalPort", "NewProtocol", "NewInternalPort", "NewInternalClient",
            "NewEnabled", "NewPortMappingDescription", "NewLeaseDuration"), add.argNames)
        assertEquals("Ash & Amy <link> \"play\"", add.args["NewPortMappingDescription"])
        assertEquals(listOf("NewRemoteHost", "NewExternalPort", "NewProtocol"), igd.calls("DeletePortMapping").single().argNames)
    }

    /**
     * A PPPoE router (common for DSL) lists an unused WANIPConnection next to the
     * WANPPPConnection that is up. If the unused one drops the connection, the next
     * service must still be tried, as it is when the unused one answers with an error.
     */
    @Test fun upnpTriesTheNextServiceWhenOneDropsTheConnection() {
        val igd = keep(RvIgd(igd("WANIPConnection:1" to "/ctl/IP", "WANPPPConnection:1" to "/ctl/PPP")))
        igd.broken += "/ctl/IP"
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget, localV4 = lo4))
        val t0 = System.nanoTime()
        val got = m.map()
        val ms = (System.nanoTime() - t0) / 1_000_000
        Thread.sleep(1500) // let a straggling UPnP thread log what happened
        assertEquals("map() took $ms ms; calls: ${igd.calls.map { it.path + " " + it.action }}; log: $logs",
            listOf(mapped("203.0.113.7")), got)
        assertEquals("/ctl/PPP", igd.calls("AddPortMapping").single().path)
    }

    @Test fun upnpResolvesControlUrlAgainstUrlBaseWithoutTrailingSlash() {
        val igd = keep(RvIgd({ p -> igd("WANIPConnection:1" to "ctl/IP", urlBase = "http://127.0.0.1:$p")(p) }))
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        assertEquals(listOf(mapped("203.0.113.7")), m.map())
        assertEquals("/ctl/IP", igd.calls("AddPortMapping").single().path)
    }

    @Test fun upnpReadsAPrefixedDescriptionAndLowercaseLocation() {
        val prefixed: (Int) -> String = { _ ->
            """<?xml version="1.0"?><d:root xmlns:d="urn:schemas-upnp-org:device-1-0"><d:device><d:serviceList>
<d:service ><d:serviceType>
   urn:schemas-upnp-org:service:WANIPConnection:1
</d:serviceType><d:controlURL>/ctl/IP?a=1&amp;b=2</d:controlURL></d:service></d:serviceList></d:device></d:root>"""
        }
        val igd = keep(RvIgd(prefixed) { "location:$it" })
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        assertEquals(listOf(mapped("203.0.113.7")), m.map())
        assertEquals("/ctl/IP", igd.calls("AddPortMapping").single().path)
    }

    /**
     * Some routers take seconds to answer AddPortMapping (they reload the firewall
     * after adding the rule). The rule exists on the router, but the app timed out:
     * nobody ever deletes it.
     */
    @Test fun upnpSlowAddPortMappingIsNotLeftOnTheRouter() {
        val igd = keep(RvIgd(igd("WANIPConnection:1" to "/ctl/IP")))
        igd.addDelayMs = 2000
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        assertEquals(emptyList<Candidate>(), m.map())
        m.close()
        assertTrue(m.awaitReleased(3000))
        Thread.sleep(2500) // the router's late answer, and anything the UPnP thread does after it
        assertEquals(1, igd.calls("AddPortMapping").size)
        assertEquals("every AddPortMapping the router applied should be deleted; log: $logs",
            igd.calls("AddPortMapping").size, igd.calls("DeletePortMapping").size)
    }

    /** The same, but close() comes while AddPortMapping is still waiting for its answer. */
    @Test fun upnpSlowAddPortMappingAfterCloseIsDeletedToo() {
        val igd = keep(RvIgd(igd("WANIPConnection:1" to "/ctl/IP")))
        igd.addDelayMs = 2000
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        thread(isDaemon = true) { m.map() }
        waitFor(3000, "the AddPortMapping") { igd.calls("AddPortMapping").isNotEmpty() }
        m.close()
        assertTrue(m.awaitReleased(3000))
        waitFor(4000, "its deletion") { igd.calls("DeletePortMapping").size == 1 }
    }

    // ---- PCP / NAT-PMP router variants

    @Test fun pcpHeaderOnlyErrorFallsBackToNatPmp() {
        val g = gw { pcpHeaderError = 2 } // NOT_AUTHORIZED, no MAP body
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(listOf(mapped("203.0.113.9")), m.map())
        assertTrue(g.requests.any { it.proto == "pmp" && it.lifetime > 0 })
        assertTrue(logs.toString(), logs.any { it.contains("NOT_AUTHORIZED") })
    }

    @Test fun natPmpRouterRejectingPcpWithOpcodeZeroStillMaps() {
        val g = gw { pcp = false; v2Reject = "op80" }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(listOf(mapped("203.0.113.9")), m.map())
    }

    @Test fun natPmpRouterIgnoringPcpStillMaps() {
        val g = gw { pcp = false; v2Reject = "none" }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(listOf(mapped("203.0.113.9")), m.map())
    }

    @Test fun pcpOnlyRouterMaps() {
        val g = gw { pmp = false; v0AsPcp = true }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(listOf(mapped("203.0.113.9")), m.map())
        assertTrue(g.requests.none { it.proto == "pmp" })
    }

    @Test fun pcpReplyWithAForeignNonceIsIgnored() {
        val g = gw { badNonce = true; pmp = false }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(emptyList<Candidate>(), m.map())
    }

    @Test fun natPmpBehindCarrierNatMapsNothing() {
        val g = gw { pcp = false; external = InetAddress.getByName("100.70.0.1") }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(emptyList<Candidate>(), m.map())
        assertTrue(g.requests.none { it.proto == "pmp" })
        assertTrue(m.describe(), m.describe().contains("100.70.0.1"))
    }

    // ---- lifetimes, renewal and close()

    @Test fun renewalRetriesWhileTheRouterIsSilentThenRecovers() {
        val g = gw { grantS = 2 }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(1, m.map().size)
        g.silent = true
        // One renewal attempt retransmits for ~2 s before it counts as failed.
        waitFor(6000, "a failed renewal") { logs.any { it.contains("will retry") } }
        g.silent = false
        val ok = g.requests.size
        waitFor(4000, "a renewal after the router is back") { g.requests.size > ok + 1 }
        assertEquals(listOf(mapped("203.0.113.9")), m.candidates())
        val nonce = g.requests.first { it.proto == "pcp" }.nonce
        assertTrue(g.requests.filter { it.proto == "pcp" }.all { it.nonce == nonce })
    }

    @Test fun pcpRenewalThatMovesThePortUpdatesCandidates() {
        val g = gw { grantS = 2 }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(listOf(mapped("203.0.113.9")), m.map())
        g.assignPort = 50001
        waitFor(4000, "the moved mapping") { m.candidates() == listOf(mapped("203.0.113.9", 50001)) }
        assertTrue(logs.toString(), logs.any { it.contains("moved the mapping") })
        m.close()
        assertTrue(m.awaitReleased(3000))
        val del = g.requests.last()
        assertEquals(0L, del.lifetime)
    }

    @Test fun closeDuringASlowRenewalReleasesLast() {
        val g = gw { grantS = 2 }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(1, m.map().size)
        g.delayMs = 700
        val n = g.requests.size
        waitFor(4000, "a renewal in flight") { g.requests.size > n }
        m.close()
        assertTrue(m.awaitReleased(5000))
        Thread.sleep(300)
        val pcp = g.requests.filter { it.proto == "pcp" }
        val lastRelease = pcp.indexOfLast { it.lifetime == 0L }
        assertTrue("no release: $pcp", lastRelease >= 0)
        assertTrue("a renewal after the release: $pcp", pcp.drop(lastRelease + 1).none { it.lifetime > 0 })
    }

    /** close() at any moment of map() (or before / after) leaves nothing on the router. */
    @Test fun closeAtAnyMomentLeavesNoMapping() {
        for (i in 0 until 12) {
            val g = RvGateway(lo4)
            val igd = RvIgd(igd("WANIPConnection:1" to "/ctl/IP"))
            try {
                val m = PortMapper(port, lo4, PortMapper.Options(serverPort = g.port, ssdpTarget = igd.ssdpTarget)) { logs += it }
                val t = thread { m.map() }
                Thread.sleep(i * 25L)
                m.close()
                t.join(4000)
                assertTrue(m.awaitReleased(4000))
                waitFor(4000, "every mapping released (round $i)") {
                    val live = g.requests.filter { it.proto == "pcp" && it.lifetime > 0 }.map { it.nonce }.toSet()
                    val gone = g.requests.filter { it.proto == "pcp" && it.lifetime == 0L }.map { it.nonce }.toSet()
                    gone.containsAll(live) && igd.calls("AddPortMapping").size == igd.calls("DeletePortMapping").size
                }
            } finally {
                g.close(); igd.close()
            }
        }
    }

    /** Many rooms in a row: no socket or thread outlives a closed PortMapper. */
    @Test fun repeatedRoomsLeakNoSocketsOrThreads() {
        val g = gw()
        val igd = keep(RvIgd(igd("WANIPConnection:1" to "/ctl/IP")))
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.UnixOperatingSystemMXBean
        fun portmapThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("portmap") }
        fun round() {
            val m = PortMapper(port, lo4, PortMapper.Options(serverPort = g.port, ssdpTarget = igd.ssdpTarget)) { logs += it }
            assertTrue(m.map().isNotEmpty())
            m.close()
            assertTrue(m.awaitReleased(4000))
        }
        round() // warm up class loading, HTTP keep-alive caches, etc.
        Thread.sleep(500)
        val fds = os.openFileDescriptorCount
        repeat(10) { round() }
        waitFor(4000, "portmap threads to end") { portmapThreads() == 0 }
        Thread.sleep(500)
        assertTrue("open fds ${os.openFileDescriptorCount} vs $fds before", os.openFileDescriptorCount <= fds + 4)
        assertEquals(igd.calls("AddPortMapping").size, igd.calls("DeletePortMapping").size)
    }

    /**
     * The first map() ran while the router was not answering yet (e.g. right after
     * joining Wi-Fi). A second map() must be able to open the port, not silently
     * create the mapping and delete it again.
     */
    @Test fun secondMapAfterTheRouterWakesUp() {
        val g = gw { silent = true }
        val m = mapper(lo4, pcpOptions(g))
        assertEquals(emptyList<Candidate>(), m.map())
        g.silent = false
        val second = m.map()
        Thread.sleep(300)
        assertEquals("pcp requests: ${g.requests}; log: $logs", listOf(mapped("203.0.113.9")), second)
    }

    // ---- address classification

    @Test fun classifiesBoundaryAddresses() {
        fun k(ip: String) = PortMapper.kindOf(InetAddress.getByName(ip))
        assertEquals(Candidate.Kind.MAPPED, k("172.15.255.255"))
        assertEquals(Candidate.Kind.MAPPED, k("100.63.255.255"))
        assertEquals(Candidate.Kind.MAPPED, k("169.253.0.1"))
        assertEquals(Candidate.Kind.MAPPED, k("192.0.1.1"))
        assertEquals(Candidate.Kind.MAPPED, k("198.17.255.255"))
        assertEquals(Candidate.Kind.MAPPED, k("198.20.0.0"))
        assertEquals(Candidate.Kind.MAPPED, k("223.255.255.255"))
        assertEquals(Candidate.Kind.MAPPED, k("1.0.0.1"))
        assertNull(k("0.255.255.255"))
        assertNull(k("192.0.0.255"))
        assertNull(k("198.18.0.1"))
        assertNull(k("224.0.0.1"))
        assertNull(k("255.255.255.255"))
        assertNull(k("240.0.0.1"))
        assertEquals(Candidate.Kind.LAN, k("fc00::1"))
        assertEquals(Candidate.Kind.LAN, k("fdff:ffff::1"))
        assertNull(k("fbff::1"))
        assertNull(k("fe80::1%1"))
        assertNull(k("fec0::1"))     // deprecated site-local
        assertNull(k("::"))
        assertNull(k("::ffff:0:0"))  // becomes 0.0.0.0
        assertNull(k("64:ff9b::808:808")) // NAT64 well-known prefix: not this phone's address
        assertNull(k("1fff:ffff::1"))
        assertEquals(Candidate.Kind.IPV6, k("3fff:ffff::1"))
        assertNull(k("4000::1"))
        // ::ffff:a.b.c.d text becomes an Inet4Address in Java: classified as IPv4.
        assertEquals(Candidate.Kind.MAPPED, k("::ffff:49.36.10.2"))
        val mapped6 = Inet6Address.getByAddress(null, ByteArray(10) + byteArrayOf(-1, -1) + byteArrayOf(10, 0, 0, 1), -1)
        assertNull(PortMapper.kindOf(mapped6)) // an explicit Inet6Address v4-mapped is not a usable v6 address
        assertTrue(!PortMapper.isPublicV4(InetAddress.getByName("100.64.0.0")))
        assertTrue(PortMapper.isPublicV4(InetAddress.getByName("100.128.0.0")))
    }
}
