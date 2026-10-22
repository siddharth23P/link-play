package com.linkplay.app.net

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A router's PCP + NAT-PMP server (RFC 6887 / RFC 6886) on loopback. */
private class FakeGateway(
    bind: InetAddress,
    private val pcp: Boolean = true,
    private val pmp: Boolean = true,
    private val silent: Boolean = false,
    private val external: InetAddress = InetAddress.getByName("203.0.113.9"),
    private val grantS: Long = 7200,
    /** A slow router: each answer waits this long. */
    private val delayMs: Long = 0,
) : Closeable {
    /** One mapping request as the router saw it ("pcp", "pmp" or "pmp-addr"). */
    data class Req(val proto: String, val lifetime: Long, val internalPort: Int, val suggestedPort: Int,
                   val client: InetAddress?, val nonce: List<Byte>?)

    private val socket = DatagramSocket(InetSocketAddress(bind, 0))
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<Req>()

    init { thread(isDaemon = true, name = "fake-gateway") { loop() } }

    private fun loop() {
        val buf = ByteArray(1500)
        while (true) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (e: IOException) { return }
            val r = buf.copyOf(p.length)
            if (silent || r.size < 2) continue
            val reply = when (r[0].toInt()) {
                2 -> if (pcp) pcpReply(r, p.address)
                     else byteArrayOf(0, 0x81.toByte(), 0, 1, 0, 0, 0, 7) // NAT-PMP: "unsupported version"
                0 -> if (pmp) pmpReply(r) else ByteArray(24).also { it[0] = 2; it[1] = 0x80.toByte(); it[3] = 1 }
                else -> null
            } ?: continue
            val to = p.socketAddress
            if (delayMs <= 0) try { socket.send(DatagramPacket(reply, reply.size, to)) } catch (e: IOException) { return } // closed
            else thread(isDaemon = true) { // seen (and recorded) at once, answered late
                Thread.sleep(delayMs)
                try { socket.send(DatagramPacket(reply, reply.size, to)) } catch (e: IOException) {}
            }
        }
    }

    private fun pcpReply(r: ByteArray, from: InetAddress): ByteArray? {
        if (r.size < 60 || r[1].toInt() != 1) return null
        val lifetime = u32(r, 4)
        val client = InetAddress.getByAddress(r.copyOfRange(8, 24))
        val nonce = r.copyOfRange(24, 36)
        val internal = u16(r, 40)
        val suggested = u16(r, 42)
        requests += Req("pcp", lifetime, internal, suggested, client, nonce.toList())
        val result = if (client == from) 0 else 12 // ADDRESS_MISMATCH
        // IPv4: our NAT's address. IPv6: a firewall, so the client's own address.
        val ext = if (client is Inet4Address) ByteArray(10) + byteArrayOf(-1, -1) + external.address else client.address
        return ByteBuffer.allocate(60).put(2).put(0x81.toByte()).put(0).put(result.toByte())
            .putInt(minOf(lifetime, grantS).toInt()).putInt(1234).put(ByteArray(12))
            .put(nonce).put(17).put(ByteArray(3)).putShort(internal.toShort())
            .putShort((if (suggested != 0) suggested else 50000).toShort()).put(ext).array()
    }

    private fun pmpReply(r: ByteArray): ByteArray? = when (r[1].toInt()) {
        0 -> {
            requests += Req("pmp-addr", 0, 0, 0, null, null)
            ByteBuffer.allocate(12).put(0).put(0x80.toByte()).putShort(0).putInt(1234).put(external.address).array()
        }
        1 -> {
            val internal = u16(r, 4)
            val suggested = u16(r, 6)
            val lifetime = u32(r, 8)
            requests += Req("pmp", lifetime, internal, suggested, null, null)
            ByteBuffer.allocate(16).put(0).put(0x81.toByte()).putShort(0).putInt(1234).putShort(internal.toShort())
                .putShort((if (lifetime == 0L) 0 else if (suggested != 0) suggested else internal).toShort())
                .putInt(minOf(lifetime, grantS).toInt()).array()
        }
        else -> null
    }

    override fun close() = socket.close()

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
}

/**
 * An Internet gateway device: a unicast SSDP responder plus the HTTP side (a nested
 * device description with a PPP and an IP connection, and the SOAP control URLs).
 */
private class FakeIgd(
    private val ipExternal: String = "203.0.113.7",
    private val pppExternal: String = "0.0.0.0",
) : Closeable {
    data class Call(val path: String, val action: String, val args: Map<String, String>)

    val calls = CopyOnWriteArrayList<Call>()
    /** UPnP error codes to answer successive AddPortMapping calls with. */
    val addErrors = ConcurrentLinkedQueue<Int>()
    private val lo = InetAddress.getByName("127.0.0.1")
    private val http = HttpServer.create(InetSocketAddress(lo, 0), 0)
    private val ssdp = DatagramSocket(InetSocketAddress(lo, 0))
    val ssdpTarget = InetSocketAddress(lo, ssdp.localPort)

    fun calls(action: String) = calls.filter { it.action == action }

    init {
        http.createContext("/rootDesc.xml") { ex ->
            val body = DESCRIPTION.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        http.createContext("/ctl/") { ex ->
            val body = ex.requestBody.use { String(it.readBytes()) }
            val (type, action) = ex.requestHeaders.getFirst("SOAPAction").trim('"').split('#')
            val args = Regex("<(New\\w+)>([^<]*)</").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            calls += Call(ex.requestURI.path, action, args)
            val external = if (ex.requestURI.path.endsWith("PPP")) pppExternal else ipExternal
            val err = if (action == "AddPortMapping") addErrors.poll() else null
            val (code, xml) = when {
                err != null -> 500 to "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
                    "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$err</errorCode>" +
                    "<errorDescription>nope</errorDescription></UPnPError></detail></s:Fault>"
                action == "GetExternalIPAddress" -> 200 to "<u:${action}Response xmlns:u=\"$type\">" +
                    "<NewExternalIPAddress>$external</NewExternalIPAddress></u:${action}Response>"
                else -> 200 to "<u:${action}Response xmlns:u=\"$type\"></u:${action}Response>"
            }
            val reply = ("<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$xml</s:Body></s:Envelope>").toByteArray()
            ex.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
            ex.sendResponseHeaders(code, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        http.start()
        thread(isDaemon = true, name = "fake-ssdp") {
            val buf = ByteArray(1500)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try { ssdp.receive(p) } catch (e: IOException) { break }
                val text = String(buf, 0, p.length)
                if (!text.startsWith("M-SEARCH")) continue
                val st = text.lines().first { it.startsWith("ST:", true) }.substringAfter(':').trim()
                val reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: $st\r\nUSN: uuid:fake::$st\r\nEXT:\r\n" +
                    "SERVER: Fake/1.0 UPnP/1.0\r\nLocation: http://127.0.0.1:${http.address.port}/rootDesc.xml\r\n\r\n").toByteArray()
                ssdp.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
    }

    override fun close() {
        ssdp.close()
        http.stop(0)
    }

    companion object {
        private const val DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
 <specVersion><major>1</major><minor>0</minor></specVersion>
 <device>
  <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
  <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
   <controlURL>/ctl/L3F</controlURL></service></serviceList>
  <deviceList><device>
   <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
   <deviceList><device>
    <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
    <serviceList>
     <service>
      <serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>
      <serviceId>urn:upnp-org:serviceId:WANPPPConn1</serviceId>
      <SCPDURL>/WANPPPCn.xml</SCPDURL><controlURL>/ctl/PPP</controlURL><eventSubURL>/evt/PPP</eventSubURL>
     </service>
     <service>
      <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
      <serviceId>urn:upnp-org:serviceId:WANIPConn1</serviceId>
      <SCPDURL>/WANIPCn.xml</SCPDURL><controlURL>/ctl/IP</controlURL><eventSubURL>/evt/IP</eventSubURL>
     </service>
    </serviceList>
   </device></deviceList>
  </device></deviceList>
 </device>
</root>"""
    }
}

class PortMapperTest {
    private val lo4 = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val logs = CopyOnWriteArrayList<String>()
    private val closers = ArrayList<Closeable>()
    private val port = 40123

    @After fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    private fun <T : Closeable> keep(c: T): T = c.also { closers += it }
    private fun sa(ip: String, port: Int) = InetSocketAddress(InetAddress.getByName(ip), port)

    /** A bound socket that never answers: an SSDP search nobody hears. */
    private fun silence(): InetSocketAddress = InetSocketAddress(lo4, keep(DatagramSocket(InetSocketAddress(lo4, 0))).localPort)

    private fun mapper(gateway: Inet4Address?, options: PortMapper.Options) =
        keep(PortMapper(port, gateway, options) { logs += it })

    private fun waitFor(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; log: $logs")
            Thread.sleep(20)
        }
    }

    @Test fun upnpMapsThePortAndDeletesItOnClose() {
        val igd = keep(FakeIgd())
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        assertEquals(listOf(Candidate(sa("203.0.113.7", port), Candidate.Kind.MAPPED)), m.map())
        val add = igd.calls("AddPortMapping").single()
        assertEquals("/ctl/IP", add.path) // WANIPConnection is preferred over WANPPPConnection
        assertEquals(mapOf("NewExternalPort" to "$port", "NewProtocol" to "UDP", "NewInternalPort" to "$port",
            "NewInternalClient" to "127.0.0.1", "NewEnabled" to "1", "NewPortMappingDescription" to "Link Play",
            "NewLeaseDuration" to "3600"), add.args.filterKeys { it != "NewRemoteHost" })
        assertTrue(m.describe(), m.describe().contains("UPnP opened 203.0.113.7:$port"))
        m.close()
        assertTrue(m.awaitReleased(3000))
        val del = igd.calls("DeletePortMapping").single()
        assertEquals("$port", del.args["NewExternalPort"])
        assertEquals("UDP", del.args["NewProtocol"])
    }

    @Test fun upnpUsesTheWanConnectionThatIsUp() {
        val igd = keep(FakeIgd(ipExternal = "0.0.0.0", pppExternal = "203.0.113.8"))
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget, localV4 = lo4))
        assertEquals(listOf(Candidate(sa("203.0.113.8", port), Candidate.Kind.MAPPED)), m.map())
        assertEquals("/ctl/PPP", igd.calls("AddPortMapping").single().path)
    }

    @Test fun upnpBehindCarrierNatMapsNothing() {
        val igd = keep(FakeIgd(ipExternal = "100.72.1.2"))
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        assertEquals(emptyList<Candidate>(), m.map())
        assertTrue(igd.calls("AddPortMapping").isEmpty())
        assertTrue(m.describe(), m.describe().contains("100.72.1.2"))
    }

    @Test fun upnpRetriesAfterConflictAndPermanentOnlyLeases() {
        val igd = keep(FakeIgd())
        igd.addErrors += listOf(718, 725) // port taken by another device; router only does permanent leases
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        val c = m.map().single()
        val adds = igd.calls("AddPortMapping")
        assertEquals(3, adds.size)
        assertEquals(listOf("3600", "3600", "0"), adds.map { it.args["NewLeaseDuration"] })
        assertNotEquals("$port", adds[1].args["NewExternalPort"])
        assertEquals(adds[1].args["NewExternalPort"], adds[2].args["NewExternalPort"])
        assertEquals(sa("203.0.113.7", adds[2].args["NewExternalPort"]!!.toInt()), c.address)
        assertTrue(adds.all { it.args["NewInternalPort"] == "$port" })
        m.close()
        assertTrue(m.awaitReleased(3000))
        assertEquals(adds[2].args["NewExternalPort"], igd.calls("DeletePortMapping").single().args["NewExternalPort"])
    }

    @Test fun upnpRenewsBeforeTheLeaseEnds() {
        val igd = keep(FakeIgd())
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget, leaseS = 2))
        assertEquals(1, m.map().size)
        waitFor(3000, "a renewal") { igd.calls("AddPortMapping").size >= 2 }
        assertEquals("$port", igd.calls("AddPortMapping")[1].args["NewExternalPort"])
    }

    @Test fun pcpMapsAndReleasesWithTheSameNonce() {
        val gw = keep(FakeGateway(lo4))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = silence()))
        assertEquals(listOf(Candidate(sa("203.0.113.9", port), Candidate.Kind.MAPPED)), m.map())
        val req = gw.requests.first { it.proto == "pcp" }
        assertEquals(3600L, req.lifetime)
        assertEquals(port, req.internalPort)
        assertEquals(port, req.suggestedPort)
        assertEquals(lo4, req.client) // the PCP client address must be the packet's source
        assertTrue(gw.requests.none { it.proto == "pmp" })
        assertTrue(m.describe(), m.describe().contains("PCP opened 203.0.113.9:$port"))
        m.close()
        assertTrue(m.awaitReleased(3000))
        val del = gw.requests.last()
        assertEquals("pcp", del.proto)
        assertEquals(0L, del.lifetime)
        assertEquals(req.nonce, del.nonce)
    }

    @Test fun natPmpWhenTheRouterHasNoPcp() {
        val gw = keep(FakeGateway(lo4, pcp = false))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = silence()))
        assertEquals(listOf(Candidate(sa("203.0.113.9", port), Candidate.Kind.MAPPED)), m.map())
        val req = gw.requests.single { it.proto == "pmp" }
        assertEquals(3600L, req.lifetime)
        assertEquals(port, req.internalPort)
        m.close()
        assertTrue(m.awaitReleased(3000))
        val del = gw.requests.last()
        assertEquals(listOf("pmp", 0L, port, 0), listOf(del.proto, del.lifetime, del.internalPort, del.suggestedPort))
    }

    @Test fun natPmpRenewsAtHalfTheGrantedLifetime() {
        val gw = keep(FakeGateway(lo4, pcp = false, grantS = 2))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = silence()))
        assertEquals(1, m.map().size)
        waitFor(3000, "a renewal") { gw.requests.count { it.proto == "pmp" } >= 2 }
        val renewal = gw.requests.filter { it.proto == "pmp" }[1]
        assertEquals(3600L, renewal.lifetime)
        assertEquals(port, renewal.suggestedPort) // asks for the port it already has
    }

    @Test fun pcpBehindCarrierNatIsReleasedAtOnce() {
        val gw = keep(FakeGateway(lo4, external = InetAddress.getByName("100.64.0.5")))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = silence()))
        assertEquals(emptyList<Candidate>(), m.map())
        val pcp = gw.requests.filter { it.proto == "pcp" }
        assertEquals(listOf(3600L, 0L), pcp.map { it.lifetime })
        assertTrue(m.describe(), m.describe().contains("100.64.0.5"))
    }

    @Test fun pcpAndUpnpTogetherGiveOneCandidate() {
        val gw = keep(FakeGateway(lo4, external = InetAddress.getByName("203.0.113.7")))
        val igd = keep(FakeIgd())
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = igd.ssdpTarget))
        assertEquals(listOf(Candidate(sa("203.0.113.7", port), Candidate.Kind.MAPPED)), m.map())
    }

    @Test fun silentRouterStaysWithinTheBudget() {
        val gw = keep(FakeGateway(lo4, silent = true))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = silence()))
        val t0 = System.nanoTime()
        assertEquals(emptyList<Candidate>(), m.map())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("map() took $ms ms", ms < 3000)
        assertTrue(m.describe(), m.describe().contains("did not open"))
        assertTrue(logs.toString(), logs.any { it.contains("no answer") })
    }

    @Test fun routerWithoutPcpPortGivesUpAtOnce() {
        val closedPort = DatagramSocket(InetSocketAddress(lo4, 0)).use { it.localPort }
        val m = mapper(lo4, PortMapper.Options(serverPort = closedPort, ssdpTarget = silence(), budgetMs = 2800))
        assertEquals(emptyList<Candidate>(), m.map())
        // Either ICMP "port unreachable" ended it at once, or (if the OS hides it) the timeout did.
        assertTrue(logs.toString(), logs.any { it.contains("does not listen") || it.contains("no answer") })
    }

    @Test fun pcpOpensAnIpv6Pinhole() {
        val lo6 = InetAddress.getByName("::1") as Inet6Address
        val gw = try { FakeGateway(lo6) } catch (e: IOException) { null }
        assumeTrue("no IPv6 loopback", gw != null)
        keep(gw!!)
        val m = mapper(null, PortMapper.Options(gateway6 = lo6, localV6 = listOf(lo6), serverPort = gw.port, ssdpTarget = silence()))
        // Our own address was already in the code: a pinhole adds no candidate.
        assertEquals(emptyList<Candidate>(), m.map())
        val req = gw.requests.single()
        assertEquals(lo6, req.client)
        assertEquals(3600L, req.lifetime)
        assertTrue(m.describe(), m.describe().contains("PCP IPv6 opened the IPv6 firewall"))
        m.close()
        assertTrue(m.awaitReleased(3000))
        assertEquals(0L, gw.requests.last().lifetime)
    }

    @Test fun mapAsyncAnswersOnItsOwnThread() {
        val igd = keep(FakeIgd())
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        val got = java.util.concurrent.ArrayBlockingQueue<Pair<String, List<Candidate>>>(1)
        m.mapAsync { got.put(Thread.currentThread().name to it) }
        val (threadName, candidates) = got.poll(4, java.util.concurrent.TimeUnit.SECONDS)!!
        assertNotEquals(Thread.currentThread().name, threadName)
        assertEquals(listOf(Candidate(sa("203.0.113.7", port), Candidate.Kind.MAPPED)), candidates)
    }

    @Test fun secondMapKeepsWhatIsOpenAndAsksForNothingTwice() {
        val gw = keep(FakeGateway(lo4))
        val igd = keep(FakeIgd())
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = igd.ssdpTarget))
        val first = m.map()
        // PCP answers in one round trip and normally wins; UPnP may still get there first on a busy
        // machine. Either way exactly one public IPv4 is kept (the other is given back).
        val pcp = listOf(Candidate(sa("203.0.113.9", port), Candidate.Kind.MAPPED))
        val upnp = listOf(Candidate(sa("203.0.113.7", port), Candidate.Kind.MAPPED))
        assertTrue("$first", first == pcp || first == upnp)
        val before = gw.requests.size
        val adds = igd.calls("AddPortMapping").size
        assertEquals(first, m.map())
        assertEquals("the router was asked again: ${gw.requests}", before, gw.requests.size)
        assertEquals(adds, igd.calls("AddPortMapping").size)
        m.close()
        assertTrue(m.awaitReleased(3000))
        assertEquals(listOf(3600L, 0L), gw.requests.filter { it.proto == "pcp" }.map { it.lifetime })
    }

    /**
     * PCP and UPnP both answered with the same public address and port: that is one rule in the router's
     * table (miniupnpd serves both), so giving the second one back would delete the port we advertise.
     */
    @Test fun theSameRuleOpenedTwiceIsNotGivenBack() {
        val gw = keep(FakeGateway(lo4, pmp = false, delayMs = 900)) // PCP answers late: UPnP gets there first
        val igd = keep(FakeIgd(ipExternal = "203.0.113.9"))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = igd.ssdpTarget, budgetMs = 2800))
        val got = m.map()
        assertEquals(listOf(Candidate(sa("203.0.113.9", port), Candidate.Kind.MAPPED)), got)
        // Both answered (whichever came second says so) and both are kept.
        waitFor(3000, "the second answer") { logs.any { it.contains("the same port the router opened already") } }
        assertEquals("nothing given back: $logs", 0, igd.calls("DeletePortMapping").size)
        assertTrue("nothing given back: ${gw.requests}", gw.requests.none { it.proto == "pcp" && it.lifetime == 0L })
        assertEquals(got, m.candidates())
        m.close()
        assertTrue(m.awaitReleased(5000))
        assertEquals("UPnP released on close", 1, igd.calls("DeletePortMapping").size)
        waitFor(3000, "PCP's release on close") { gw.requests.any { it.proto == "pcp" && it.lifetime == 0L } }
    }

    @Test fun secondMapDoesNotOpenAnIpv6PinholeTwice() {
        val lo6 = InetAddress.getByName("::1") as Inet6Address
        val gw = try { FakeGateway(lo6) } catch (e: IOException) { null }
        assumeTrue("no IPv6 loopback", gw != null)
        keep(gw!!)
        val m = mapper(null, PortMapper.Options(gateway6 = lo6, localV6 = listOf(lo6), serverPort = gw.port, ssdpTarget = silence()))
        m.map()
        m.map()
        assertEquals(1, gw.requests.size)
    }

    /** Two map() calls at once (e.g. a retry button pressed twice) ask the router once. */
    @Test fun concurrentMapsShareOneRequest() {
        val gw = keep(FakeGateway(lo4, pmp = false))
        val m = mapper(lo4, PortMapper.Options(serverPort = gw.port, ssdpTarget = silence()))
        val other = java.util.concurrent.ArrayBlockingQueue<List<Candidate>>(1)
        thread { other.put(m.map()) }
        val mine = m.map()
        val expected = listOf(Candidate(sa("203.0.113.9", port), Candidate.Kind.MAPPED))
        assertEquals(expected, mine)
        assertEquals(expected, other.poll(4, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(gw.requests.toString(), 1, gw.requests.count { it.proto == "pcp" })
    }

    @Test fun mapAfterCloseKeepsNothing() {
        val igd = keep(FakeIgd())
        val m = mapper(null, PortMapper.Options(ssdpTarget = igd.ssdpTarget))
        m.close()
        assertEquals(emptyList<Candidate>(), m.map())
        waitFor(3000, "the late mapping's release") { igd.calls("DeletePortMapping").size == igd.calls("AddPortMapping").size }
    }

    @Test fun classifiesAddresses() {
        fun k(ip: String) = PortMapper.kindOf(InetAddress.getByName(ip))
        assertEquals(Candidate.Kind.LAN, k("10.1.2.3"))
        assertEquals(Candidate.Kind.LAN, k("172.16.0.1"))
        assertEquals(Candidate.Kind.LAN, k("172.31.255.1"))
        assertEquals(Candidate.Kind.LAN, k("192.168.1.7"))
        assertEquals(Candidate.Kind.MAPPED, k("172.32.0.1"))
        assertEquals(Candidate.Kind.MAPPED, k("49.36.10.2"))
        assertEquals(Candidate.Kind.MAPPED, k("100.128.0.1"))
        assertNull(k("100.64.0.1"))   // carrier-grade NAT
        assertNull(k("100.127.255.1"))
        assertNull(k("192.0.0.4"))    // 464XLAT (Jio's IPv4 on an IPv6-only network)
        assertNull(k("127.0.0.1"))
        assertNull(k("169.254.3.4"))
        assertNull(k("0.0.0.0"))
        assertNull(k("239.255.255.250"))
        assertEquals(Candidate.Kind.IPV6, k("2401:4900:1c2a::5"))
        assertEquals(Candidate.Kind.IPV6, k("2409:40c1:1000::1"))
        assertEquals(Candidate.Kind.LAN, k("fd7a:115c:a1e0::1")) // ULA
        assertNull(k("fe80::1"))
        assertNull(k("::1"))
        assertNull(k("ff02::1"))
        assertTrue(PortMapper.isPublicV4(InetAddress.getByName("203.0.113.7")))
        assertTrue(!PortMapper.isPublicV4(InetAddress.getByName("2401:4900::1")))
    }
}
