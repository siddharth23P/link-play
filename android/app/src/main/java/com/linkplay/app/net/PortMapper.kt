package com.linkplay.app.net

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Asks the home router to open a UDP port for the room's socket, so a friend in
 * another city can reach a phone that sits on Wi-Fi behind IPv4 NAT. No server is
 * involved; we only talk to our own router:
 *  - PCP (RFC 6887, MAP) and NAT-PMP (RFC 6886) to the default gateway. Both are
 *    asked at once, so one round trip tells which one the router speaks.
 *  - UPnP IGD: SSDP search, device description, WANIPConnection / WANPPPConnection
 *    GetExternalIPAddress + AddPortMapping.
 *  - PCP over IPv6 for our global IPv6 addresses: home routers that support it open
 *    a firewall pinhole. That adds no candidate (the address is already in the
 *    code); it just lets a guest in without the reply-code round trip.
 *
 * Everything is best effort. map() blocks for at most ~3 s (call it off the main
 * thread) and returns a MAPPED candidate only for a public external IPv4: when the
 * router's own WAN address is private or carrier-grade NAT (double NAT), nobody
 * outside can reach a mapping on our router, so it is released at once.
 * map() may be called again (e.g. the router was not answering yet right after
 * joining Wi-Fi); it only asks for what is not open already.
 * Mappings are renewed at half their lifetime and given back by close().
 */
class PortMapper(
    /** The room socket's UDP port (Transport.port); also asked for as the external port. */
    private val port: Int,
    /** IPv4 default gateway, where PCP / NAT-PMP live; null skips them. */
    private val gateway: Inet4Address?,
    private val options: Options = Options(),
    private val log: (String) -> Unit = {},
) : Closeable {

    class Options(
        /** Our LAN IPv4 for UPnP's NewInternalClient; found by routing toward the router when null. */
        val localV4: Inet4Address? = null,
        /** IPv6 default router (usually link-local, so it needs its scope) for PCP pinholes; null skips them. */
        val gateway6: Inet6Address? = null,
        /** Our global IPv6 addresses to open pinholes for (the first two are tried). */
        val localV6: List<Inet6Address> = emptyList(),
        /** PCP / NAT-PMP server port; tests point it at a fake server. */
        val serverPort: Int = 5351,
        /** Where the SSDP M-SEARCH goes; tests point it at a unicast responder. */
        val ssdpTarget: InetSocketAddress = InetSocketAddress("239.255.255.250", 1900),
        /** Requested lifetime of each mapping, seconds. */
        val leaseS: Int = 3600,
        /** How long map() may take in total. */
        val budgetMs: Long = 2800,
        /** Android: Network.bindSocket, so router traffic leaves on the room's network. */
        val udpHook: (DatagramSocket) -> Unit = {},
        /** Android: Network.openConnection, for the same reason. */
        val openUrl: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        val description: String = "Link Play",
    )

    /** A port the router opened for us; [local] is the IPv6 address of a pinhole (null for IPv4). */
    private abstract class Mapping(val via: String, @Volatile var external: InetSocketAddress, @Volatile var lifetimeS: Long,
                                   val local: InetAddress? = null) {
        var renewal: ScheduledFuture<*>? = null
        /** Asks for the same mapping again: the new (address, lifetime), or null if the router said no. */
        abstract fun renew(): Pair<InetSocketAddress, Long>?
        abstract fun release()
        /** What it adds to a code: a public IPv4 is MAPPED; a pinhole for our own IPv6 adds nothing. */
        open fun kind(): Candidate.Kind? = if (isPublicV4(external.address)) Candidate.Kind.MAPPED else null
    }

    private class PcpReply(val result: Int, val lifetime: Long, val external: InetSocketAddress?)

    private val random = SecureRandom()
    private val lock = Any()
    private val mappings = ArrayList<Mapping>()
    /** UPnP adds whose answer never came: the router may hold them, so close() deletes them too. */
    private val unconfirmed = ArrayList<Mapping>()
    private val jobs = HashMap<String, Thread>() // each protocol's latest thread
    private var running = 0 // map() calls in progress: results that arrive while none is were never advertised
    private var closed = false
    @Volatile private var doubleNat: InetAddress? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portmap").apply { isDaemon = true } }

    /**
     * Blocking, at most about Options.budgetMs: tries every protocol in parallel and
     * returns the candidates open now (normally zero or one MAPPED address).
     */
    fun map(): List<Candidate> {
        val deadline = now() + options.budgetMs
        val started = synchronized(lock) {
            if (closed) return emptyList()
            running++
            listOf<Pair<String, () -> Unit>>(
                "portmap-gw" to { guard("PCP/NAT-PMP") { viaGateway(deadline) } },
                "portmap-v6" to { guard("PCP IPv6") { pinholes(deadline) } },
                "portmap-upnp" to { guard("UPnP") { viaUpnp(deadline) } },
            ).map { (name, job) ->
                // Still busy from an earlier map() (a slow router): wait for it rather than ask twice.
                jobs[name]?.takeIf { it.isAlive } ?: thread(isDaemon = true, name = name, block = job).also { jobs[name] = it }
            }
        }
        try {
            for (t in started) t.join(max(1L, deadline + 300 - now()))
        } finally {
            synchronized(lock) { running-- }
        }
        return candidates()
    }

    /** map() on its own thread; [onDone] runs there (never on the caller's, e.g. Android's main thread). */
    fun mapAsync(onDone: (List<Candidate>) -> Unit) {
        thread(isDaemon = true, name = "portmap") { onDone(map()) }
    }

    fun candidates(): List<Candidate> = synchronized(lock) {
        mappings.mapNotNull { m -> m.kind()?.let { Candidate(m.external, it) } }.distinct()
    }

    /** One line for the UI. */
    fun describe(): String = synchronized(lock) {
        val parts = mappings.map { m ->
            if (m.kind() == null) "${m.via} opened the IPv6 firewall for ${m.external.address.hostAddress}"
            else "${m.via} opened ${m.external.address.hostAddress}:${m.external.port}"
        }
        when {
            parts.isNotEmpty() -> "Router: " + parts.joinToString("; ")
            doubleNat != null -> "Router is behind another NAT (its internet address is ${doubleNat!!.hostAddress}), so it cannot open a port"
            else -> "Router did not open a port (no UPnP / NAT-PMP / PCP)"
        }
    }

    /** Gives every mapping back to the router, in the background: never blocks. */
    override fun close() {
        val gone = synchronized(lock) {
            if (closed) return
            closed = true
            mappings.forEach { it.renewal?.cancel(false) }
            (mappings + unconfirmed).also { mappings.clear(); unconfirmed.clear() }
        }
        timer.execute { gone.forEach { release(it) } }
        timer.shutdown()
    }

    /** For tests and app exit: waits until close() has released everything. */
    fun awaitReleased(timeoutMs: Long): Boolean = timer.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    // ---- bookkeeping

    private fun adopt(m: Mapping) {
        // PCP / NAT-PMP and UPnP run at once: when both get a port, the slower one is given back
        // (one public IPv4 is all a code needs, and each would take a slot in it). Unless it is the
        // same external address and port: then it is the same rule in the router's one table
        // (miniupnpd serves all three protocols), and giving it back would delete the port we keep.
        // It is kept and renewed alongside (candidates() lists the address once); close() deletes both.
        var second = false
        var same = false
        val keep = synchronized(lock) {
            val v4 = mappings.filter { it.kind() == Candidate.Kind.MAPPED }
            if (m.kind() == Candidate.Kind.MAPPED && v4.isNotEmpty()) {
                same = v4.any { it.external == m.external }
                second = !same
            }
            (running > 0 && !closed && !second).also { if (it) mappings += m }
        }
        if (second) log("${m.via}: ${m.external} not needed, a port is open already")
        if (!keep) return release(m)
        log("${m.via}: ${m.external} open for ${if (m.lifetimeS == 0L) "good" else "${m.lifetimeS} s"}" +
            if (same) " (the same port the router opened already)" else "")
        schedule(m, m.lifetimeS / 2)
    }

    /** Never advertised, only deleted by close() (at once if that already ran). */
    private fun holdUntilClose(m: Mapping) {
        val keep = synchronized(lock) { (!closed).also { if (it) unconfirmed += m } }
        if (!keep) release(m)
    }

    /** A public IPv4 is open already (one is all a code needs). */
    private fun hasV4() = synchronized(lock) { mappings.any { it.kind() == Candidate.Kind.MAPPED } }

    private fun schedule(m: Mapping, delayS: Long) {
        if (m.lifetimeS <= 0) return // permanent UPnP lease: nothing to renew
        synchronized(lock) {
            if (!closed) m.renewal = timer.schedule(Runnable { renew(m) }, max(1L, delayS), TimeUnit.SECONDS)
        }
    }

    private fun renew(m: Mapping) {
        val got = try { m.renew() } catch (e: IOException) { null }
        if (got == null) {
            log("${m.via}: renewing ${m.external} failed, will retry")
            return schedule(m, min(30L, m.lifetimeS / 4))
        }
        if (got.first != m.external) log("${m.via}: the router moved the mapping to ${got.first}; codes shared earlier still say ${m.external}")
        m.external = got.first
        m.lifetimeS = got.second
        schedule(m, m.lifetimeS / 2)
    }

    private fun release(m: Mapping) = guard("${m.via} release") {
        m.release()
        log("${m.via}: released ${m.external}")
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("$what: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ---- PCP / NAT-PMP on the IPv4 gateway

    private fun viaGateway(deadline: Long) {
        val gw = gateway ?: return
        if (hasV4()) return // a later map(): nothing to add
        val server = InetSocketAddress(gw, options.serverPort)
        val nonce = ByteArray(12).also(random::nextBytes)
        var pcp: PcpReply? = null
        var pcpRefused = false // a NAT-PMP-only router, or PCP said no
        var pmpExternal: InetAddress? = null
        try {
            udp(server, null) { s ->
                var settleBy = min(deadline, now() + 1500)
                val requests = listOf(pcpRequest(s.localAddress, nonce, options.leaseS.toLong(), null), byteArrayOf(0, 0))
                exchange(s, requests, { settleBy }) { r ->
                    when {
                        r.size >= 24 && r[0] == 2.toByte() && r[1] == 0x81.toByte() -> {
                            val reply = parsePcp(r, nonce)
                            if (reply?.external != null) pcp = reply
                            else if (reply != null && !pcpRefused) { pcpRefused = true; log("PCP: router says ${pcpResult(reply.result)}") }
                        }
                        // A NAT-PMP router answers our version-2 request with "unsupported version".
                        r.size >= 4 && r[0] == 0.toByte() && r[1] == 0x81.toByte() -> pcpRefused = true
                        r.size >= 12 && r[0] == 0.toByte() && r[1] == 0x80.toByte() && u16(r, 2) == 0 -> {
                            pmpExternal = InetAddress.getByAddress(r.copyOfRange(8, 12))
                            settleBy = min(settleBy, now() + 300) // give PCP a moment, it is the better of the two
                        }
                    }
                    pcp != null || (pmpExternal != null && pcpRefused)
                }
            }
        } catch (e: PortUnreachableException) {
            return log("PCP/NAT-PMP: the router does not listen on port ${options.serverPort}")
        }
        val p = pcp
        val pmpExt = pmpExternal
        when {
            p != null -> {
                val ext = p.external!!
                val m = pcpMapping("PCP", server, null, nonce, ext, p.lifetime)
                if (!isPublicV4(ext.address)) {
                    noteDoubleNat("PCP", ext.address)
                    return release(m)
                }
                adopt(m)
            }
            pmpExt != null -> {
                if (!isPublicV4(pmpExt)) return noteDoubleNat("NAT-PMP", pmpExt)
                if (hasV4()) return
                val (extPort, life) = pmpMap(server, options.leaseS.toLong(), port, deadline) ?: return log("NAT-PMP: router did not map the port")
                adopt(object : Mapping("NAT-PMP", InetSocketAddress(pmpExt, extPort), life) {
                    override fun renew() = pmpMap(server, options.leaseS.toLong(), external.port, now() + 2000)
                        ?.let { (p2, l2) -> InetSocketAddress(external.address, p2) to l2 }
                    // RFC 6886 3.4: a deletion has lifetime 0 and suggested external port 0.
                    override fun release() { pmpMap(server, 0, 0, now() + 1000) }
                })
            }
            else -> log("PCP/NAT-PMP: no answer from ${gw.hostAddress}")
        }
    }

    private fun noteDoubleNat(via: String, wan: InetAddress) {
        doubleNat = wan
        log("$via: the router's internet address ${wan.hostAddress} is not public (another NAT in front of it), so a port mapping cannot help")
    }

    /** A PCP mapping (IPv4, or an IPv6 pinhole when [bindTo] is our IPv6 address). */
    private fun pcpMapping(via: String, server: InetSocketAddress, bindTo: InetAddress?, nonce: ByteArray,
                           ext: InetSocketAddress, life: Long) = object : Mapping(via, ext, life, bindTo) {
        override fun renew() = pcpOnce(server, bindTo, nonce, options.leaseS.toLong(), external, now() + 2000)
            ?.takeIf { it.result == 0 && it.external != null }?.let { it.external!! to it.lifetime }
        // Same nonce, lifetime 0 (RFC 6887 15).
        override fun release() { pcpOnce(server, bindTo, nonce, 0, external, now() + 1000) }
        override fun kind(): Candidate.Kind? = when {
            bindTo == null -> super.kind()
            // Our own address behind a firewall: only a pinhole. A translated one (NPTv6) is new.
            external.address != bindTo && kindOf(external.address) == Candidate.Kind.IPV6 -> Candidate.Kind.IPV6
            else -> null
        }
    }

    /** One PCP MAP request on a fresh socket; null when the router stays silent. */
    private fun pcpOnce(server: InetSocketAddress, bindTo: InetAddress?, nonce: ByteArray, lifetime: Long,
                        suggest: InetSocketAddress?, deadline: Long): PcpReply? {
        var got: PcpReply? = null
        udp(server, bindTo) { s ->
            exchange(s, listOf(pcpRequest(s.localAddress, nonce, lifetime, suggest)), { deadline }) { r ->
                got = parsePcp(r, nonce); got != null
            }
        }
        return got
    }

    /** NAT-PMP UDP mapping request: (external port, lifetime) on success. */
    private fun pmpMap(server: InetSocketAddress, lifetime: Long, suggestPort: Int, deadline: Long): Pair<Int, Long>? {
        val req = ByteBuffer.allocate(12).put(0).put(1).putShort(0).putShort(port.toShort())
            .putShort(suggestPort.toShort()).putInt(lifetime.toInt()).array()
        var got: Pair<Int, Long>? = null
        var answered = false
        udp(server, null) { s ->
            exchange(s, listOf(req), { deadline }) { r ->
                if (r.size >= 16 && r[0] == 0.toByte() && r[1] == 0x81.toByte() && u16(r, 8) == port) {
                    answered = true
                    val result = u16(r, 2)
                    if (result == 0 && (lifetime == 0L || u32(r, 12) > 0)) got = u16(r, 10) to u32(r, 12)
                    else log("NAT-PMP: router says ${PMP_RESULTS.getOrElse(result) { "error $result" }}")
                }
                answered
            }
        }
        return got
    }

    // ---- PCP IPv6 firewall pinholes

    private fun pinholes(deadline: Long) {
        val gw = options.gateway6 ?: return
        val server = InetSocketAddress(gw, options.serverPort)
        for (addr in options.localV6.take(2)) {
            if (synchronized(lock) { mappings.any { it.local == addr } }) continue // a later map(): open already
            val nonce = ByteArray(12).also(random::nextBytes)
            val r = try {
                pcpOnce(server, addr, nonce, options.leaseS.toLong(), null, min(deadline, now() + 1500))
            } catch (e: PortUnreachableException) { null }
            // The same router answers for every address: silence once means silence for all.
            if (r == null) return log("PCP IPv6: no answer from the router")
            if (r.result != 0 || r.external == null) { log("PCP IPv6: router says ${pcpResult(r.result)}"); continue }
            adopt(pcpMapping("PCP IPv6", server, addr, nonce, r.external, r.lifetime))
        }
    }

    // ---- UPnP IGD

    private fun viaUpnp(deadline: Long) {
        if (hasV4()) return // a later map(): nothing to add
        val locations = ssdp(min(deadline, now() + 1200))
        if (locations.isEmpty()) return log("UPnP: no Internet gateway device answered")
        for (loc in locations) {
            val services = try { services(loc, deadline) } catch (e: IOException) {
                log("UPnP: $loc: ${e.message}"); continue
            }
            for ((type, ctl) in services) {
                if (now() >= deadline || hasV4()) return
                try {
                    val ext = externalIp(type, ctl, deadline) ?: continue
                    if (!isPublicV4(ext)) { noteDoubleNat("UPnP", ext); continue }
                    if (hasV4()) return // PCP / NAT-PMP got there while we asked
                    val me = options.localV4 ?: routeTo(ctl.host) ?: continue
                    val m = upnpMap(type, ctl, ext, me, deadline) ?: continue
                    return adopt(m)
                } catch (e: IOException) {
                    // E.g. a PPPoE router's unused WANIPConnection drops the connection: the next service may be up.
                    log("UPnP: $ctl: ${e.message}")
                }
            }
        }
    }

    /** SSDP M-SEARCH: the description URLs of the gateways that answered, ours first. */
    private fun ssdp(until: Long): List<URL> {
        val found = LinkedHashSet<URL>()
        DatagramSocket().use { s ->
            options.udpHook(s)
            val requests = SEARCH_TARGETS.map {
                "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $it\r\n\r\n".toByteArray()
            }
            var end = until
            exchange(s, requests, { end }, options.ssdpTarget) { r ->
                val loc = String(r, Charsets.ISO_8859_1).split("\r\n", "\n")
                    .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.let { runCatching { URL(it) }.getOrNull() }
                if (loc != null && loc.protocol == "http" && found.add(loc)) end = min(end, now() + 300) // others answer together
                loc != null && loc.host == gateway?.hostAddress
            }
        }
        return found.sortedBy { if (it.host == gateway?.hostAddress) 0 else 1 }
    }

    /** (serviceType, controlURL) of the device's WAN connection services, best first. */
    private fun services(loc: URL, deadline: Long): List<Pair<String, URL>> {
        val (code, xml) = http(loc, deadline, null, null)
        if (code != 200) throw IOException("description: HTTP $code")
        val base = tag(xml, "URLBase")?.let { runCatching { URL(it) }.getOrNull() } ?: loc
        return SERVICE.findAll(xml).mapNotNull { m ->
            val type = tag(m.groupValues[1], "serviceType")?.takeIf { it in WAN_SERVICES } ?: return@mapNotNull null
            val ctl = tag(m.groupValues[1], "controlURL")?.let { runCatching { URL(base, it) }.getOrNull() }
            ctl?.takeIf { it.protocol == "http" }?.let { type to it }
        }.sortedBy { WAN_SERVICES.indexOf(it.first) }.toList()
    }

    private fun externalIp(type: String, ctl: URL, deadline: Long): InetAddress? {
        val (code, xml) = soap(type, ctl, "GetExternalIPAddress", emptyList(), deadline)
        if (code != 200) return null
        return tag(xml, "NewExternalIPAddress")?.let(::parseV4)?.takeIf { !it.isAnyLocalAddress } // 0.0.0.0: that WAN link is down
    }

    private fun upnpMap(type: String, ctl: URL, ext: InetAddress, me: Inet4Address, deadline: Long): Mapping? {
        var extPort = port
        var lease = options.leaseS
        repeat(4) {
            val err = try { addPortMapping(type, ctl, extPort, me, lease, deadline) } catch (e: IOException) {
                // No answer in time, yet the router may have added it (some reload their firewall
                // first, which takes seconds), maybe for good (lease 0): make sure close() deletes it.
                holdUntilClose(upnpMapping(type, ctl, InetSocketAddress(ext, extPort), me, lease))
                throw e
            }
            when (err) {
                0 -> return upnpMapping(type, ctl, InetSocketAddress(ext, extPort), me, lease)
                725 -> if (lease != 0) lease = 0 else return null // OnlyPermanentLeasesSupported
                718 -> extPort = 1024 + random.nextInt(64512)  // ConflictInMappingEntry: another device has it
                else -> { log("UPnP: AddPortMapping failed ($err)"); return null }
            }
        }
        return null
    }

    private fun upnpMapping(type: String, ctl: URL, ext: InetSocketAddress, me: Inet4Address, lease: Int) =
        object : Mapping("UPnP", ext, lease.toLong()) {
            override fun renew() = if (addPortMapping(type, ctl, external.port, me, lease, now() + 3000) == 0)
                external to lifetimeS else null
            override fun release() {
                soap(type, ctl, "DeletePortMapping", listOf("NewRemoteHost" to "",
                    "NewExternalPort" to "${external.port}", "NewProtocol" to "UDP"), now() + 2000)
            }
        }

    /** 0 on success, else the UPnP error code (or minus the HTTP status). */
    private fun addPortMapping(type: String, ctl: URL, extPort: Int, me: Inet4Address, lease: Int, deadline: Long): Int {
        val (code, xml) = soap(type, ctl, "AddPortMapping", listOf(
            "NewRemoteHost" to "", "NewExternalPort" to "$extPort", "NewProtocol" to "UDP",
            "NewInternalPort" to "$port", "NewInternalClient" to me.hostAddress, "NewEnabled" to "1",
            "NewPortMappingDescription" to options.description, "NewLeaseDuration" to "$lease"), deadline)
        return if (code == 200) 0 else tag(xml, "errorCode")?.toIntOrNull() ?: -code
    }

    private fun soap(type: String, ctl: URL, action: String, args: List<Pair<String, String>>, deadline: Long): Pair<Int, String> {
        val body = buildString {
            append("<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$type\">")
            for ((k, v) in args) append("<$k>").append(xmlEscape(v)).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>\r\n")
        }
        return http(ctl, deadline, "$type#$action", body)
    }

    private fun http(url: URL, deadline: Long, soapAction: String?, body: String?): Pair<Int, String> {
        val c = options.openUrl(url)
        try {
            val t = (deadline - now()).coerceIn(100L, 1500L).toInt()
            c.connectTimeout = t
            c.readTimeout = t
            c.instanceFollowRedirects = false
            c.useCaches = false
            if (body != null) {
                val bytes = body.toByteArray()
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                c.setRequestProperty("SOAPAction", "\"$soapAction\"")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { input ->
                // A description is a few KB; never trust a router to stop sending.
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < 256 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** Our address on the router's network: the kernel's source address toward [host]. */
    private fun routeTo(host: String): Inet4Address? = try {
        parseV4(host)?.let { router ->
            DatagramSocket().use { s ->
                options.udpHook(s)
                s.connect(InetSocketAddress(router, 1900))
                s.localAddress as? Inet4Address
            }
        }
    } catch (e: IOException) { null }

    // ---- UDP plumbing

    /** A fresh socket (bound to [bindTo], or the wildcard) connected to [server]: only its replies get in. */
    private inline fun <T> udp(server: InetSocketAddress, bindTo: InetAddress?, block: (DatagramSocket) -> T): T =
        DatagramSocket(null as SocketAddress?).use { s ->
            s.bind(InetSocketAddress(bindTo, 0))
            options.udpHook(s) // before connect: Android cannot re-route a connected UDP socket
            s.connect(server)
            block(s)
        }

    /**
     * Sends [requests], retransmitting at 250 ms and then doubling (RFC 6886 3.1), until
     * [onReply] has what it needs or the deadline passes. ICMP "port unreachable" on a
     * connected socket ends it at once with PortUnreachableException.
     */
    private inline fun exchange(s: DatagramSocket, requests: List<ByteArray>, deadline: () -> Long,
                                to: SocketAddress? = null, onReply: (ByteArray) -> Boolean): Boolean {
        val buf = ByteArray(2048)
        var wait = 250L
        var nextSend = now()
        while (true) {
            val t = now()
            if (t >= deadline()) return false
            if (t >= nextSend) {
                for (r in requests) s.send(if (to == null) DatagramPacket(r, r.size) else DatagramPacket(r, r.size, to))
                nextSend = t + wait
                wait *= 2
            }
            s.soTimeout = max(1L, min(nextSend, deadline()) - t).toInt()
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
            if (onReply(buf.copyOf(p.length))) return true
        }
    }

    /** RFC 6887 MAP request for UDP [port]: 24-byte header + 36-byte MAP body. */
    private fun pcpRequest(client: InetAddress, nonce: ByteArray, lifetime: Long, suggest: InetSocketAddress?): ByteArray {
        // No preference for the external address = the all-zeros address of the client's family.
        val anyExt = if (client is Inet4Address) v6Bytes(InetAddress.getByAddress(ByteArray(4))) else ByteArray(16)
        return ByteBuffer.allocate(60)
            .put(2).put(1).putShort(0).putInt(lifetime.toInt()).put(v6Bytes(client))
            .put(nonce).put(17).put(ByteArray(3)).putShort(port.toShort())
            .putShort((suggest?.port ?: port).toShort())
            .put(suggest?.let { v6Bytes(it.address) } ?: anyExt)
            .array()
    }

    private fun parsePcp(r: ByteArray, nonce: ByteArray): PcpReply? {
        if (r.size < 24 || r[0] != 2.toByte() || r[1] != 0x81.toByte()) return null
        val result = r[3].toInt() and 0xff
        // Errors about the header itself may come without the MAP body.
        if (r.size < 60) return if (result != 0) PcpReply(result, 0, null) else null
        if (!r.copyOfRange(24, 36).contentEquals(nonce) || r[36] != 17.toByte() || u16(r, 40) != port) return null
        val ext = InetSocketAddress(fromV6Bytes(r.copyOfRange(44, 60)), u16(r, 42))
        return PcpReply(result, u32(r, 4), ext.takeIf { result == 0 })
    }

    companion object {
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )
        private val WAN_SERVICES = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )
        private val SERVICE = Regex("<(?:\\w+:)?service\\b[^>]*>(.*?)</(?:\\w+:)?service\\s*>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val PCP_RESULTS = listOf("SUCCESS", "UNSUPP_VERSION", "NOT_AUTHORIZED", "MALFORMED_REQUEST",
            "UNSUPP_OPCODE", "UNSUPP_OPTION", "MALFORMED_OPTION", "NETWORK_FAILURE", "NO_RESOURCES",
            "UNSUPP_PROTOCOL", "USER_EX_QUOTA", "CANNOT_PROVIDE_EXTERNAL", "ADDRESS_MISMATCH", "EXCESSIVE_REMOTE_PEERS")
        private val PMP_RESULTS = listOf("success", "unsupported version", "not authorized / refused",
            "network failure", "out of resources", "unsupported opcode")

        /**
         * How a local address can appear in a code: LAN (private IPv4, or IPv6 ULA fc00::/7 —
         * same site, or an overlay VPN), IPV6 (global unicast 2000::/3), MAPPED (public IPv4),
         * or null when no peer can use it (loopback, link-local, CGNAT 100.64/10, 464XLAT
         * 192.0.0.0/29, multicast...).
         */
        fun kindOf(a: InetAddress): Candidate.Kind? {
            val b = a.address
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            return if (a is Inet4Address) when {
                x == 10 || (x == 172 && y in 16..31) || (x == 192 && y == 168) -> Candidate.Kind.LAN
                isPublicV4(a) -> Candidate.Kind.MAPPED
                else -> null
            } else when {
                (x and 0xfe) == 0xfc -> Candidate.Kind.LAN
                (x and 0xe0) == 0x20 -> Candidate.Kind.IPV6
                else -> null
            }
        }

        /**
         * A public IPv4: not "this network", private, CGNAT, loopback, link-local,
         * 192.0.0.0/24, benchmarking or multicast/reserved. (Documentation ranges such as
         * 203.0.113.0/24 count as public, which lets tests use them.)
         */
        fun isPublicV4(a: InetAddress): Boolean {
            if (a !is Inet4Address) return false
            val b = a.address
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            val z = b[2].toInt() and 0xff
            return !(x == 0 || x == 10 || x == 127 || x >= 224 || (x == 100 && y in 64..127) ||
                (x == 169 && y == 254) || (x == 172 && y in 16..31) || (x == 192 && y == 168) ||
                (x == 192 && y == 0 && z == 0) || (x == 198 && y in 18..19))
        }

        private fun pcpResult(code: Int) = PCP_RESULTS.getOrElse(code) { "error $code" }

        /** IPv6 bytes as PCP carries addresses: an IPv4 one as ::ffff:a.b.c.d. */
        private fun v6Bytes(a: InetAddress): ByteArray =
            if (a is Inet4Address) ByteArray(10) + byteArrayOf(-1, -1) + a.address else a.address

        private fun fromV6Bytes(b: ByteArray): InetAddress =
            if (b.copyOfRange(0, 12).contentEquals(ByteArray(10) + byteArrayOf(-1, -1))) InetAddress.getByAddress(b.copyOfRange(12, 16))
            else InetAddress.getByAddress(b)

        /** A dotted-quad literal, or null: text from the router must never cause a DNS lookup. */
        private fun parseV4(s: String): Inet4Address? {
            val parts = s.trim().split('.')
            if (parts.size != 4) return null
            val b = ByteArray(4)
            for (i in 0..3) b[i] = (parts[i].takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 } ?: return null).toByte()
            return InetAddress.getByAddress(b) as Inet4Address
        }

        private fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name\\b[^>]*>(.*?)</", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(xml)?.groupValues?.get(1)?.trim()?.let(::xmlUnescape)

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        private fun xmlUnescape(s: String) =
            s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        private fun u32(b: ByteArray, i: Int) = ((u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong())
        private fun now() = System.nanoTime() / 1_000_000
    }
}
