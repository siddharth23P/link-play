package com.linkplay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.system.OsConstants
import com.linkplay.app.net.Candidate
import com.linkplay.app.net.NatCheck
import com.linkplay.app.net.PortMapper
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where this phone can be reached, worked out on the phone alone (STUN only if the
 * user allows it, in PhoneLink): the addresses Android gives the network our traffic
 * uses, and a PortMapper for that network's router.
 *
 * For a room:
 *   val net = NetEnv.snapshot(context)               // or snapshot(context, mobileNetwork)
 *   val hold = net.keepAlive(context)                 // close when the room ends
 *   val transport = UdpTransport(key, isHost, TransportOptions(socketHook = net.socketHook))
 *   val mapper = net.portMapper(transport.port, log)  // null unless Wi-Fi / Ethernet
 *   // off the main thread (map() may take ~3 s; or mapper.mapAsync { ... }):
 *   val candidates = net.candidates(transport.port) + (mapper?.map() ?: emptyList())
 *   // room over: mapper?.close(); hold.close()
 *
 * Mobile IPv6 while on Wi-Fi: Android can keep mobile data up next to Wi-Fi
 * (requestCellular + Network.bindSocket), but a socket lives on ONE network, so
 * offering the Wi-Fi addresses and the mobile IPv6 address together would need a
 * second socket in the transport. We do not do that. Instead the whole room can run
 * on mobile data while Wi-Fi stays connected: requestCellular, then
 * snapshot(context, thatNetwork). describe() suggests it when it would help.
 */
object NetEnv {
    /** Invite codes hold 8 candidates: keep room for LAN and a port mapping. */
    private const val MAX_V6 = 3

    /** One network as the room will use it. Plain data: take a new snapshot after a network change. */
    class Snapshot internal constructor(
        /** The network the room's socket is bound to; null when there is none. */
        val network: Network?,
        /** "Wi-Fi", "mobile data", "Ethernet", "VPN", "network" or "no network". */
        val label: String,
        val isVpn: Boolean,
        val isCellular: Boolean,
        /** Behind a home router we can ask for a port mapping (Wi-Fi or Ethernet). */
        val homeRouter: Boolean,
        /** Private IPv4 (10/8, 172.16/12, 192.168/16): friends on the same Wi-Fi. */
        val lanV4: List<Inet4Address>,
        /** An IPv4 right on the interface that looks public (some carriers really do that; others squat public blocks). */
        val publicV4: List<Inet4Address>,
        /** Global IPv6, temporary (privacy) addresses first: outgoing packets use those. */
        val globalV6: List<Inet6Address>,
        /** Unique local IPv6 (fc00::/7): same site, or an overlay VPN such as Tailscale. */
        val ulaV6: List<Inet6Address>,
        /** IPv4 default gateway (PCP / NAT-PMP server). */
        val gateway: Inet4Address?,
        /** IPv6 default router, scoped to its interface (PCP pinholes). */
        val gateway6: Inet6Address?,
        /** On Wi-Fi without IPv6 while mobile data (still up in the background) has IPv6. */
        val mobileHasIpv6: Boolean,
    ) {
        val hasIpv6 get() = globalV6.isNotEmpty()

        /**
         * This network's own addresses for a code, at the transport's [port]. On mobile data a private
         * IPv4 sits behind the carrier's NAT (no LAN a friend can reach), and an IPv4 that merely looks
         * public is often one of the blocks carriers use internally: it goes in only once [nat] (the
         * optional STUN self-test) saw exactly that address, see NatCheck.ipv4Candidates.
         */
        fun candidates(port: Int, nat: NatCheck? = null): List<Candidate> {
            fun of(addrs: List<InetAddress>, kind: Candidate.Kind) = addrs.map { Candidate(InetSocketAddress(it, port), kind) }
            val lan = if (isCellular) emptyList() else of(lanV4.take(2), Candidate.Kind.LAN)
            return lan + of(ulaV6.take(1), Candidate.Kind.LAN) + of(globalV6.take(MAX_V6), Candidate.Kind.IPV6) +
                NatCheck.ipv4Candidates(publicV4.take(1).map { InetSocketAddress(it, port) }, isCellular, nat)
        }

        /**
         * For TransportOptions.socketHook: binds the room's socket to this network, so it
         * keeps using the addresses in the code even if Android switches the default
         * network (e.g. home Wi-Fi comes in range during a game on mobile data).
         */
        val socketHook: (DatagramSocket) -> Unit = { socket ->
            // If the network is already gone, the socket stays on the default one;
            // the transport's timeouts report the broken room.
            try { network?.bindSocket(socket) } catch (e: IOException) {}
        }

        /** Asks this network's router to open the port; null where there is no home router. */
        fun portMapper(port: Int, log: (String) -> Unit = {}): PortMapper? {
            if (!homeRouter || network == null) return null
            return PortMapper(port, gateway, PortMapper.Options(
                localV4 = lanV4.firstOrNull(), gateway6 = gateway6, localV6 = globalV6,
                // NO_PROXY: openConnection(url) would send even 192.168.x.1 through the Wi-Fi's HTTP proxy
                // (an explicit proxy ignores its exclusion list), and the router is only reachable directly.
                udpHook = socketHook, openUrl = { url -> network.openConnection(url, Proxy.NO_PROXY) as HttpURLConnection },
            ), log)
        }

        /**
         * Keeps a mobile-data network up while the room uses it: without a request of
         * our own Android tears it down ~30 s after Wi-Fi becomes the default. No-op
         * for other networks.
         */
        fun keepAlive(context: Context): Closeable =
            if (isCellular && !isVpn) requestCellular(context) {} else Closeable {}

        /** One line for the UI: what friends elsewhere can reach, and what to do if nothing. */
        fun describe(): String {
            if (network == null) return "No network: connect to Wi-Fi or mobile data"
            val sb = StringBuilder(label)
            sb.append(if (hasIpv6) ", IPv6 ${globalV6[0].hostAddress}" else ", no IPv6")
            lanV4.firstOrNull()?.let { sb.append(", LAN ${it.hostAddress}") }
            publicV4.firstOrNull()?.let {
                // On mobile data it is often a carrier-internal block that only looks public.
                sb.append(if (isCellular) ", IPv4 ${it.hostAddress} (not offered unless STUN confirms it)" else ", public IPv4 ${it.hostAddress}")
            }
            sb.append(when {
                isVpn -> ". A VPN is on: friends reach you only through it; turn it off to play directly"
                hasIpv6 -> ""
                mobileHasIpv6 -> ". Mobile data has IPv6: turn Wi-Fi off (or use mobile data for the room) so friends elsewhere can reach you"
                homeRouter -> ". Friends elsewhere can reach you only if the router opens a port (UPnP / NAT-PMP / PCP)"
                else -> ". Friends elsewhere may not reach you: this network has no IPv6"
            })
            return sb.toString()
        }
    }

    fun snapshot(context: Context, network: Network? = null): Snapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = network ?: cm.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (lp == null || caps == null) {
            return Snapshot(null, "no network", false, false, false, emptyList(), emptyList(), emptyList(),
                emptyList(), null, null, false)
        }
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val label = when {
            vpn -> "VPN"
            wifi -> "Wi-Fi"
            ethernet -> "Ethernet"
            cellular -> "mobile data"
            else -> "network"
        }
        val a = Addresses(lp)
        val homeRouter = !vpn && (wifi || ethernet)
        val mobileV6 = homeRouter && a.global.isEmpty() && mobileHasIpv6(cm, net)
        return Snapshot(net, label, vpn, cellular, homeRouter, a.lan, a.public, a.global, a.ula,
            gateway4(lp), gateway6(lp), mobileV6)
    }

    fun candidates(context: Context, port: Int): List<Candidate> = snapshot(context).candidates(port)
    fun socketHook(context: Context): (DatagramSocket) -> Unit = snapshot(context).socketHook
    fun gateway(context: Context): Inet4Address? = snapshot(context).gateway
    fun describe(context: Context): String = snapshot(context).describe()

    /**
     * Asks Android to bring up (or keep up) mobile data next to Wi-Fi; [onResult] runs
     * once, on a ConnectivityManager thread, with the network or null (none within
     * [timeoutMs], or not allowed). Close the handle when the room ends: until then
     * Android keeps mobile data up for us.
     */
    fun requestCellular(context: Context, timeoutMs: Int = 10_000, onResult: (Network?) -> Unit): Closeable {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val done = AtomicBoolean()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Since Android O onLinkPropertiesChanged follows onAvailable at once; only then
            // are the addresses there for snapshot().
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (done.compareAndSet(false, true)) onResult(network)
            }
            override fun onUnavailable() {
                if (done.compareAndSet(false, true)) onResult(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, callback, timeoutMs)
        } catch (e: RuntimeException) { // SecurityException without CHANGE_NETWORK_STATE, too many requests...
            if (done.compareAndSet(false, true)) onResult(null)
            return Closeable {}
        }
        return Closeable {
            try { cm.unregisterNetworkCallback(callback) } catch (e: IllegalArgumentException) {} // already released
        }
    }

    /** A network's usable addresses, sorted by what a peer can do with them. */
    private class Addresses(lp: LinkProperties) {
        val lan = ArrayList<Inet4Address>()
        val public = ArrayList<Inet4Address>()
        val ula = ArrayList<Inet6Address>()
        val global: List<Inet6Address>

        init {
            val temporary = ArrayList<Inet6Address>()
            val stable = ArrayList<Inet6Address>()
            for (la in lp.linkAddresses) {
                val addr = la.address
                // Deprecated addresses are being phased out; tentative / failed ones are not usable yet.
                if (addr is Inet6Address && (la.flags and UNUSABLE) != 0) continue
                when (PortMapper.kindOf(addr)) {
                    Candidate.Kind.LAN -> if (addr is Inet4Address) lan += addr else ula += addr as Inet6Address
                    Candidate.Kind.IPV6 -> if ((la.flags and OsConstants.IFA_F_TEMPORARY) != 0) temporary += addr as Inet6Address
                                           else stable += addr as Inet6Address
                    Candidate.Kind.MAPPED -> public += addr as Inet4Address
                    null -> {} // loopback, link-local, CGNAT, 464XLAT's 192.0.0.4...
                }
            }
            global = temporary + stable
        }

        companion object {
            private val UNUSABLE = OsConstants.IFA_F_DEPRECATED or OsConstants.IFA_F_TENTATIVE or OsConstants.IFA_F_DADFAILED
        }
    }

    /**
     * The default route of one family that has a next hop. RouteInfo.hasGateway() is public only
     * from API 29 (minSdk is 28: hidden there, so the call may die with NoSuchMethodError); a route
     * without a next hop holds the any-address instead, which is what hasGateway() checks.
     */
    private inline fun <reified T : InetAddress> defaultRoute(lp: LinkProperties): RouteInfo? =
        lp.routes.firstOrNull { r -> r.isDefaultRoute && r.gateway.let { it is T && !it.isAnyLocalAddress } }

    private fun gateway4(lp: LinkProperties): Inet4Address? = defaultRoute<Inet4Address>(lp)?.gateway as Inet4Address?

    /** The IPv6 default router is link-local, so it is only usable with its interface attached. */
    private fun gateway6(lp: LinkProperties): Inet6Address? {
        val route = defaultRoute<Inet6Address>(lp) ?: return null
        val gw = route.gateway as Inet6Address
        if (!gw.isLinkLocalAddress || gw.scopeId != 0) return gw
        return try {
            Inet6Address.getByAddress(null, gw.address, NetworkInterface.getByName(route.`interface` ?: lp.interfaceName))
        } catch (e: Exception) { null }
    }

    /** Is mobile data up next to [current] (Android keeps it on by default) with a global IPv6? */
    @Suppress("DEPRECATION") // allNetworks: a callback would be async, and we only want a snapshot
    private fun mobileHasIpv6(cm: ConnectivityManager, current: Network): Boolean = cm.allNetworks.any { n ->
        n != current && cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true && cm.getLinkProperties(n)?.linkAddresses?.any { PortMapper.kindOf(it.address) == Candidate.Kind.IPV6 } == true
    }
}
