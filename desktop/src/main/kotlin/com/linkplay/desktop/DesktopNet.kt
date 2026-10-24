package com.linkplay.desktop

import com.linkplay.app.net.Candidate
import com.linkplay.app.net.NatCheck
import com.linkplay.app.net.PortMapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Where this computer can be reached, worked out on the computer alone (STUN only if the user allows
 * it, in [DesktopLink]). The desktop twin of the phone app's NetEnv: the same [Snapshot] (addresses,
 * a PortMapper for the router, one line of advice), but built from what plain Java can see.
 *
 * Java has no "the network my traffic uses", so we ask the kernel: connecting a UDP socket to a
 * public address sends nothing, it only makes the system pick a route, and the socket then tells us
 * the source address it chose. That address (one for IPv4, one for IPv6) names the interface the
 * link should use, which leaves out Docker, VM, Bluetooth and VPN-helper interfaces without a list
 * of names. With no route at all (a Wi-Fi with no internet, two computers on one cable) the best
 * ordinary interface is used instead, so a LAN game still works.
 *
 * Call [snapshot] off the UI thread: finding the router's address runs a short command.
 */
object DesktopNet {
    /** Invite codes hold 8 candidates: keep room for LAN and a port mapping. */
    private const val MAX_V6 = 3

    /** How long a command (route, networksetup) may take before we give up on it. */
    private const val COMMAND_MS = 1500L

    /** One network as the room will use it. Plain data: take a new snapshot after a network change. */
    class Snapshot internal constructor(
        /** Some interface with a usable address carries our traffic; false when there is none. */
        val online: Boolean,
        /** "Wi-Fi", "Ethernet", "VPN", "network" or "no network". */
        val label: String,
        /** The interface's name (en0, eth0...), null when offline. */
        val ifaceName: String?,
        val isVpn: Boolean,
        /** Behind a home router we can ask for a port mapping (a private IPv4 address, no VPN). */
        val homeRouter: Boolean,
        /** Private IPv4 (10/8, 172.16/12, 192.168/16): friends on the same Wi-Fi. The one we send from comes first. */
        val lanV4: List<Inet4Address>,
        /** An IPv4 right on the interface that looks public (some ISPs really do that). */
        val publicV4: List<Inet4Address>,
        /** Global IPv6; the address new connections go out from (the privacy one) comes first. */
        val globalV6: List<Inet6Address>,
        /** Unique local IPv6 (fc00::/7): same site, or an overlay VPN such as Tailscale. */
        val ulaV6: List<Inet6Address>,
        /** IPv4 default gateway (PCP / NAT-PMP server); null when it could not be found. */
        val gateway: Inet4Address?,
        /** IPv6 default router, scoped to its interface (PCP pinholes); null when none or not needed. */
        val gateway6: Inet6Address?,
    ) {
        val hasIpv6 get() = globalV6.isNotEmpty()

        // A phone can be on mobile data; a computer is not. Constants so the screens' shared wording compiles.
        val isCellular get() = false
        val mobileHasIpv6 get() = false

        /** Reads like the phone's `network` (null when there is no network), so its screen text ports as it is. */
        val network: Any? get() = if (online) this else null

        /**
         * This network's own addresses for a code, at the transport's [port]: up to two private IPv4,
         * one unique-local IPv6, up to three global IPv6 and one public-looking IPv4 (see NatCheck for how
         * the STUN answer [nat] adds our public address).
         */
        fun candidates(port: Int, nat: NatCheck? = null): List<Candidate> {
            if (!online) return emptyList()
            fun of(addrs: List<InetAddress>, kind: Candidate.Kind) = addrs.map { Candidate(InetSocketAddress(it, port), kind) }
            return of(lanV4.take(2), Candidate.Kind.LAN) + of(ulaV6.take(1), Candidate.Kind.LAN) +
                of(globalV6.take(MAX_V6), Candidate.Kind.IPV6) +
                NatCheck.ipv4Candidates(publicV4.take(1).map { InetSocketAddress(it, port) }, false, nat)
        }

        /** Asks this network's router to open the port; null where there is no home router. */
        fun portMapper(port: Int, log: (String) -> Unit = {}): PortMapper? {
            if (!homeRouter) return null
            return PortMapper(port, gateway, PortMapper.Options(
                localV4 = lanV4.firstOrNull(), gateway6 = gateway6, localV6 = globalV6,
                // NO_PROXY: openConnection(url) would send even 192.168.x.1 through the system's HTTP proxy,
                // and the router is only reachable directly.
                openUrl = { url -> url.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
            ), log)
        }

        /** One line for the screens: what friends elsewhere can reach, and what to do if nothing. */
        fun describe(): String {
            if (!online) return "No network: connect to Wi-Fi or Ethernet"
            val sb = StringBuilder(label)
            sb.append(if (hasIpv6) ", IPv6 ${globalV6[0].hostAddress}" else ", no IPv6")
            lanV4.firstOrNull()?.let { sb.append(", LAN ${it.hostAddress}") }
            publicV4.firstOrNull()?.let { sb.append(", public IPv4 ${it.hostAddress}") }
            sb.append(when {
                isVpn -> ". A VPN is on: friends reach you only through it; turn it off to play directly"
                hasIpv6 -> ""
                homeRouter -> ". Friends elsewhere can reach you only if the router opens a port (UPnP / NAT-PMP / PCP)"
                else -> ". Friends elsewhere may not reach you: this network has no IPv6"
            })
            return sb.toString()
        }

        companion object {
            internal fun offline() = Snapshot(false, "no network", null, false, false, emptyList(), emptyList(),
                emptyList(), emptyList(), null, null)
        }
    }

    /** The network the link would use right now. Call off the UI thread (it may run the `route` command). */
    fun snapshot(): Snapshot {
        val src4 = routeSource("8.8.8.8")
        val src6 = routeSource("2001:4860:4860::8888")
        var if4 = src4?.let { ifaceOf(it) }
        var if6 = src6?.let { ifaceOf(it) }
        var primary = if4 ?: if6
        if (primary == null) {
            // No route out (no internet, or no network): a LAN-only network is still worth playing on.
            primary = fallbackInterface()
            if (primary == null) return Snapshot.offline()
            if4 = primary
        }
        val vpn = looksLikeVpn(primary)
        // IPv6 from a different interface than the main one (a VPN's tunnel, say) is only reachable through that
        // interface's own network, so it does not go into the code.
        if (if6 != null && if6 != primary && looksLikeVpn(if6)) if6 = null

        val lan = ArrayList<Inet4Address>()
        val pub = ArrayList<Inet4Address>()
        val ula = ArrayList<Inet6Address>()
        val global = ArrayList<Inet6Address>()
        // The address the system sends from goes first: it is the one outgoing packets (and so the firewall
        // and NAT state the friend's reply meets) use. Java has no flag for temporary or deprecated addresses.
        for (a in sortedFirst(addressesOf(if4), src4)) {
            if (a !is Inet4Address) continue
            when (PortMapper.kindOf(a)) {
                Candidate.Kind.LAN -> lan += a
                Candidate.Kind.MAPPED -> pub += a
                else -> {}
            }
        }
        val v6iface = if6 ?: primary
        for (a in sortedFirst(addressesOf(v6iface), src6)) {
            if (a !is Inet6Address) continue
            when (PortMapper.kindOf(a)) {
                Candidate.Kind.LAN -> ula += a
                // Teredo and 6to4 look global but only work through a relay: a friend cannot use them.
                Candidate.Kind.IPV6 -> if (!isTransitionV6(a)) global += a
                else -> {}
            }
        }
        // A full-tunnel VPN leaves only the tunnel's own (10.x) address on the route's interface, but a friend on
        // the same Wi-Fi can still reach the ordinary interface: offer its addresses after the tunnel's.
        if (vpn) {
            val plain = fallbackInterface()
            if (plain != null && plain != primary) {
                for (a in addressesOf(plain)) {
                    if (a is Inet4Address && PortMapper.kindOf(a) == Candidate.Kind.LAN && a !in lan) lan += a
                    if (global.isEmpty() && a is Inet6Address && PortMapper.kindOf(a) == Candidate.Kind.IPV6 && !isTransitionV6(a)) global += a
                }
            }
        }
        val usable = lan.isNotEmpty() || pub.isNotEmpty() || ula.isNotEmpty() || global.isNotEmpty()
        if (!usable) return Snapshot.offline()

        val homeRouter = !vpn && lan.isNotEmpty()
        // Only a PortMapper needs the router's address, and only a home router gets one.
        val gw = if (homeRouter) gateways(primary, if4, if6, src4, src6, wantV6 = global.isNotEmpty()) else Gateways(null, null)
        return Snapshot(true, if (vpn) "VPN" else labelOf(primary), primary.name, vpn, homeRouter, lan, pub, global, ula,
            gw.v4, gw.v6)
    }

    /**
     * A cheap summary of the network for the link's watcher (every few seconds): the addresses of every
     * interface that is up, and where the system routes to. It differs after a network change (Wi-Fi in
     * or out of range, a cable, a VPN, sleep and wake). No command is run. Link-local addresses and the
     * interfaces that churn on their own (macOS's AWDL) are left out so they do not cause false alarms.
     */
    fun fingerprint(): String = try {
        val parts = ArrayList<String>()
        for (i in NetworkInterface.getNetworkInterfaces()) {
            if (!usable(i) || NOISY_NAME.containsMatchIn(i.name)) continue
            for (a in i.interfaceAddresses) {
                val addr = a.address
                if (addr.isLinkLocalAddress || addr.isLoopbackAddress) continue
                parts += "${i.name}=${addr.hostAddress}"
            }
        }
        parts.sort()
        parts += "route4=" + (routeSource("8.8.8.8")?.hostAddress ?: "-")
        parts += "route6=" + (routeSource("2001:4860:4860::8888")?.hostAddress ?: "-")
        parts.joinToString("|")
    } catch (e: Exception) {
        "error"
    }

    // ---------------------------------------------------------------- interfaces

    /**
     * The address the system would send a packet to [dst] (an IP literal: no lookup) from, or null when
     * there is no route. Nothing is sent: connect on a UDP socket only picks the route.
     */
    private fun routeSource(dst: String): InetAddress? = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(dst), 53)
            s.localAddress?.takeIf { !it.isAnyLocalAddress && !it.isLoopbackAddress }
        }
    } catch (e: Exception) { // SocketException, and since JDK 17 an UncheckedIOException ("No route to host")
        null
    }

    private fun ifaceOf(a: InetAddress): NetworkInterface? = try {
        NetworkInterface.getByInetAddress(a)?.takeIf { usable(it) }
    } catch (e: Exception) { null }

    private fun usable(i: NetworkInterface): Boolean = try { i.isUp && !i.isLoopback } catch (e: Exception) { false }

    private fun addressesOf(i: NetworkInterface?): List<InetAddress> =
        i?.interfaceAddresses?.map { it.address }?.distinct() ?: emptyList()

    private fun sortedFirst(list: List<InetAddress>, first: InetAddress?): List<InetAddress> =
        if (first == null || first !in list) list else listOf(first) + list.filter { it != first }

    /** With no route: the ordinary interface (not virtual, not a VPN) with a private IPv4, else any with an address. */
    private fun fallbackInterface(): NetworkInterface? {
        val all = try { NetworkInterface.getNetworkInterfaces().toList().filter { usable(it) && !isVirtual(it) } } catch (e: Exception) { return null }
        val withAddr = all.filter { i -> addressesOf(i).any { PortMapper.kindOf(it) != null } }
        return withAddr.firstOrNull { i -> addressesOf(i).any { it is Inet4Address && PortMapper.kindOf(it) == Candidate.Kind.LAN } }
            ?: withAddr.firstOrNull()
    }

    private val NOISY_NAME = Regex("^(awdl|llw|anpi|ap|gif|stf)\\d*$", RegexOption.IGNORE_CASE)
    private val VPN_NAME = Regex("^(utun|ppp|ipsec|tun|tap|wg|zt|tailscale)\\d*", RegexOption.IGNORE_CASE)
    // "tunnel adapter" is how Windows names the tunnel interfaces of VPN clients; bare "tunnel" is not listed:
    // "Teredo Tunneling Pseudo-Interface" and ISATAP are dealt with by the address filter (isTransitionV6).
    private val VPN_DISPLAY = Regex("tap-windows|wintun|wireguard|openvpn|\\bvpn\\b|tailscale|zerotier|anyconnect|globalprotect|\\btap\\b|" +
        "nordlynx|proton|mullvad|surfshark|pangp|fortinet|forticlient|sonicwall|juniper|pulse secure|tunnel adapter", RegexOption.IGNORE_CASE)
    private val VIRTUAL_NAME = Regex("^(lo|gif|stf|anpi|awdl|llw|ap|bridge|vmnet|vboxnet|docker|veth|virbr|br-|vnic)", RegexOption.IGNORE_CASE)
    private val VIRTUAL_DISPLAY = Regex("hyper-v|vethernet|virtualbox|vmware|loopback|bluetooth|teredo|isatap|6to4|wan miniport|virtual", RegexOption.IGNORE_CASE)

    /** True when an interface's display name (Windows: the adapter description) is a VPN client's. */
    internal fun displayLooksLikeVpn(display: String): Boolean = VPN_DISPLAY.containsMatchIn(display)

    private fun looksLikeVpn(i: NetworkInterface): Boolean {
        val display = i.displayName ?: ""
        val p2p = try { i.isPointToPoint } catch (e: Exception) { false }
        return VPN_NAME.containsMatchIn(i.name) || VPN_DISPLAY.containsMatchIn(display) || (p2p && !windows)
    }

    private fun isVirtual(i: NetworkInterface): Boolean =
        looksLikeVpn(i) || VIRTUAL_NAME.containsMatchIn(i.name) || VIRTUAL_DISPLAY.containsMatchIn(i.displayName ?: "")

    // ---------------------------------------------------------------- label

    private val osName = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT)
    private val mac = osName.contains("mac")
    private val windows = osName.contains("windows")

    /** macOS: interface name -> hardware port name ("en0" -> "Wi-Fi"), read once. */
    private val macPorts: Map<String, String> by lazy {
        val out = runCommand("/usr/sbin/networksetup", "-listallhardwareports") ?: return@lazy emptyMap()
        val map = HashMap<String, String>()
        var port: String? = null
        for (line in out.lineSequence()) {
            when {
                line.startsWith("Hardware Port:") -> port = line.substringAfter(':').trim()
                line.startsWith("Device:") -> port?.let { map[line.substringAfter(':').trim()] = it }
            }
        }
        map
    }

    /** "Wi-Fi", "Ethernet" or "network", as far as the name tells. The screens only compare with "Wi-Fi". */
    private fun labelOf(i: NetworkInterface): String {
        val text = when {
            mac -> macPorts[i.name] ?: ""
            windows -> i.displayName ?: ""
            else -> i.name // Linux: wlp3s0, wlan0 / enp0s31f6, eth0
        }.lowercase(Locale.ROOT)
        return when {
            text.contains("wi-fi") || text.contains("wifi") || text.contains("wireless") || text.contains("wlan") ||
                text.contains("802.11") || text.contains("airport") || (!mac && !windows && text.startsWith("wl")) -> "Wi-Fi"
            text.contains("ethernet") || text.contains("gbe") || text.contains("gigabit") || text.contains("lan") ||
                (!mac && !windows && (text.startsWith("en") || text.startsWith("eth"))) -> "Ethernet"
            else -> "network"
        }
    }

    // ---------------------------------------------------------------- default gateways

    private class Gateways(val v4: Inet4Address?, val v6: Inet6Address?)

    private class Cached(val key: String, val at: Long, val value: Gateways)

    @Volatile private var cache: Cached? = null

    /**
     * The router's addresses for PCP / NAT-PMP. Java has no API for them, so a short command runs; the answer
     * is kept while the addresses stay the same (a minute at most when it found nothing, ten when it did).
     * Null on any failure: PortMapper then skips PCP / NAT-PMP, and UPnP (which finds the router itself) still runs.
     */
    private fun gateways(primary: NetworkInterface, if4: NetworkInterface?, if6: NetworkInterface?,
                         src4: InetAddress?, src6: InetAddress?, wantV6: Boolean): Gateways {
        val key = "${primary.name}|${if4?.name}|${if6?.name}|${src4?.hostAddress}|${src6?.hostAddress}|$wantV6"
        val now = System.nanoTime()
        cache?.let {
            val ttl = if (it.value.v4 != null) 10 * 60 else 60
            if (it.key == key && now - it.at < ttl * 1_000_000_000L) return it.value
        }
        val v4 = try { gateway4(if4 ?: primary, src4) } catch (e: Exception) { null }
        val v6 = if (wantV6) try { gateway6(if6 ?: primary, src6) } catch (e: Exception) { null } else null
        return Gateways(v4, v6).also { cache = Cached(key, now, it) }
    }

    private fun gateway4(iface: NetworkInterface, src: InetAddress?): Inet4Address? {
        val text: String? = when {
            mac -> {
                // The default route may leave through another interface (Ethernet next to Wi-Fi): ask for ours.
                val first = runCommand("/sbin/route", "-n", "get", "default")
                if (first != null && macField(first, "interface") == iface.name) first
                else runCommand("/sbin/route", "-n", "get", "-ifscope", iface.name, "default") ?: first
            }
            windows -> runCommand("route", "print", "-4", "0.0.0.0")
            else -> return linuxGateway4(iface.name)
        }
        text ?: return null
        val gw: String? = if (mac) {
            macField(text, "gateway")
        } else {
            windowsGateway4(text, src?.hostAddress)
        }
        return (gw?.let { parseAddress(it) } as? Inet4Address)?.takeIf { !it.isAnyLocalAddress }
    }

    private fun gateway6(iface: NetworkInterface, src: InetAddress?): Inet6Address? {
        val raw = when {
            mac -> {
                val out = runCommand("/sbin/route", "-n", "get", "-inet6", "-ifscope", iface.name, "default")
                    ?: runCommand("/sbin/route", "-n", "get", "-inet6", "default")
                out?.let { macField(it, "gateway") }
            }
            windows -> runCommand("route", "print", "-6")?.let { windowsGateway6(it, iface.index) }
            else -> null
        } ?: return null
        val addr = parseAddress(raw.substringBefore('%')) as? Inet6Address ?: return null
        if (!addr.isLinkLocalAddress) return addr
        // A link-local router is only usable with its interface attached.
        return try { Inet6Address.getByAddress(null, addr.address, iface) } catch (e: Exception) { null }
    }

    private fun macField(text: String, name: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()

    private val WIN_ROUTE4 = Regex("^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\S+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+)")
    private val WIN_ROUTE6 = Regex("^\\s*(\\d+)\\s+(\\d+)\\s+::/0\\s+(\\S+)")
    private val IPV4 = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")

    /**
     * Windows `route print -4 0.0.0.0`: rows "0.0.0.0 0.0.0.0 <gateway> <our address> <metric>". The row of our own
     * address with the lowest metric wins. A gateway column that is not an address means "on-link" (whatever
     * the language of the system is), and is skipped.
     */
    private fun windowsGateway4(text: String, ourAddress: String?): String? {
        var best: String? = null
        var bestMetric = Int.MAX_VALUE
        for (line in text.lineSequence()) {
            val m = WIN_ROUTE4.find(line) ?: continue
            val (gw, iface, metric) = m.destructured
            if (!IPV4.matches(gw) || (ourAddress != null && iface != ourAddress)) continue
            val metricN = metric.toIntOrNull() ?: continue
            if (metricN < bestMetric) { best = gw; bestMetric = metricN }
        }
        return best
    }

    /** Windows `route print -6`: rows "<if index> <metric> ::/0 <gateway>" (the gateway column may read "On-link"). */
    private fun windowsGateway6(text: String, ifIndex: Int): String? {
        var best: String? = null
        var bestMetric = Int.MAX_VALUE
        for (line in text.lineSequence()) {
            val m = WIN_ROUTE6.find(line) ?: continue
            val (index, metric, gw) = m.destructured
            if (index.toIntOrNull() != ifIndex || !gw.contains(':')) continue
            val metricN = metric.toIntOrNull() ?: continue
            if (metricN < bestMetric) { best = gw; bestMetric = metricN }
        }
        return best
    }

    /** Linux: /proc/net/route, destination 00000000 on our interface; the gateway is a little-endian hex word. */
    private fun linuxGateway4(ifname: String): Inet4Address? {
        val f = File("/proc/net/route")
        if (!f.isFile) return null
        for (line in f.readLines().drop(1)) {
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 3 || c[0] != ifname || c[1] != "00000000") continue
            val v = c[2].toLongOrNull(16) ?: continue
            if (v == 0L) continue
            val bytes = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
            return InetAddress.getByAddress(bytes) as? Inet4Address
        }
        return null
    }

    /**
     * Teredo (2001:0000::/32) and 6to4 (2002::/16) addresses: tunnels through someone else's relay, so a friend
     * cannot reach them directly. Windows keeps a Teredo address on many machines; it must not count as IPv6.
     */
    internal fun isTransitionV6(a: InetAddress): Boolean {
        val b = a.address
        if (b.size != 16) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val teredo = b0 == 0x20 && b1 == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0
        return teredo || (b0 == 0x20 && b1 == 0x02)
    }

    /** An IP literal to an address; null for anything else (never a lookup). */
    private fun parseAddress(s: String): InetAddress? {
        val t = s.trim()
        if (!IPV4.matches(t) && !t.contains(':')) return null
        return try { InetAddress.getByName(t) } catch (e: Exception) { null }
    }

    /** Runs a command and returns what it printed; null if it failed, was not found or took longer than [COMMAND_MS]. */
    private fun runCommand(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        // A reader thread: a command that fills the pipe (Windows' small one) must not wait for us, and one that
        // hangs must not hold us.
        val reader = Thread({
            try { p.inputStream.copyTo(out) } catch (_: Exception) {}
        }, "net-command").apply { isDaemon = true; start() }
        if (!p.waitFor(COMMAND_MS, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            Log.w("network: ${cmd[0]} took too long")
            null
        } else {
            reader.join(300)
            if (p.exitValue() == 0) out.toString(Charsets.UTF_8) else null
        }
    } catch (e: Exception) {
        null
    }
}
