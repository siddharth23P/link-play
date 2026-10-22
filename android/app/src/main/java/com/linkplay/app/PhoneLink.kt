package com.linkplay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.linkplay.app.net.Candidate
import com.linkplay.app.net.Codes
import com.linkplay.app.net.Control
import com.linkplay.app.net.GuestSession
import com.linkplay.app.net.HostRoom
import com.linkplay.app.net.Invite
import com.linkplay.app.net.LinkBackend
import com.linkplay.app.net.LinkListener
import com.linkplay.app.net.NatCheck
import com.linkplay.app.net.Pipe
import com.linkplay.app.net.PipeHandler
import com.linkplay.app.net.PortMapper
import com.linkplay.app.net.Protocol
import com.linkplay.app.net.Reply
import com.linkplay.app.net.RomId
import com.linkplay.app.net.RoomPlayer
import com.linkplay.app.net.TransportOptions
import com.linkplay.app.net.UdpTransport
import java.io.Closeable
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Link play with nothing but phones: a room hosted on this phone ([hosting]) or our
 * place in a friend's, found through invite / reply codes. Owns everything that must
 * be given back when it ends: the UDP transport, the room or guest session, the
 * mobile-data request, the router port mapping and the network watch.
 *
 * Network work runs on its own thread. [onUpdate] runs on the main thread whenever
 * something the screens show changed (code, reachability, progress, failure); the
 * room's own events go straight from the backend to [listener],
 * plus (host) a news line per guest saying whether it got in with the invite alone
 * or only after its reply code was pasted.
 *
 * Everything worth knowing after a real test goes to logcat under the tag "LinkPlay"
 * (`adb logcat -s LinkPlay`): the network chosen, the NAT self-test, "probing <connId> -> ...",
 * "<connId> PROBE from <address>" (guest: the host's probes got through), "<connId> pipe open via
 * <path> (invite only|after reply code)", "host: <name> (<connId>) is in the room via ...", and
 * strangers that were limited. Never a code: an invite holds the room's secret, so the log names the
 * room id (host) or connection id (guest) and the code's version instead.
 */
class PhoneLink(
    context: Context,
    val hosting: Boolean,
    /** Host: a fresh Codes.newInvite (its addresses are filled in here). Guest: the invite being joined. */
    invite: Invite,
    private val myName: String,
    private val rom: RomId,
    /** The user allowed asking a public STUN server for our public IPv4 (Advanced, off by default). */
    private val askStun: Boolean,
    /** The user wants the link on the current Wi-Fi even without IPv6 (Advanced): never move to mobile data. */
    private val stayOnWifi: Boolean,
    private val listener: LinkListener,
    private val onUpdate: (PhoneLink) -> Unit,
) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-link").apply { isDaemon = true } }
    private val lock = Any()

    val key = invite.key
    /** Guest: our connection id; the host finds it in our reply code. */
    val connId = Codes.newConnId()

    // What the screens show. Written on the worker, read on the main thread after onUpdate.
    /** Host: the invite to share, remade when our addresses change. Guest: the invite we're joining. */
    @Volatile var invite = invite; private set
    /** Host: the invite text. Guest: the reply text. Empty until our addresses are known. */
    @Volatile var code = ""; private set
    /** Bumped each time the host's invite is remade after a network change. */
    @Volatile var inviteVersion = 0; private set
    /** Bumped each time the guest's reply code changes after it was first shown (router port, network change). */
    @Volatile var replyVersion = 0; private set
    /** The network the link runs on. */
    @Volatile var net: NetEnv.Snapshot? = null; private set
    /** The link's network is gone and no other is up: [code] holds addresses that no longer work. */
    val offline: Boolean get() = net?.network == null
    /** Wi-Fi had no IPv6, so the link runs on mobile data (Wi-Fi stays on for everything else). */
    @Volatile var onMobileData = false; private set
    /** Whether the router opened a port; null when there was no router to ask. */
    @Volatile var routerPort: Boolean? = null; private set
    /** The STUN self-test (only when the user allowed STUN); null until it ran. */
    @Volatile var nat: NatCheck? = null; private set
    /** Guest: the path to the host once the transport got through (we're joining the room then). */
    @Volatile var connectedVia: String? = null; private set
    /** Guest: the host was probing us when we got through, so it had pasted our reply code. */
    @Volatile var connectedAfterReply = false; private set
    /** What setup is doing right now; empty when idle. */
    @Volatile var progress = ""; private set
    /** Setup failed for good; the link is useless. */
    @Volatile var failure: String? = null; private set
    /** The room (host) or guest session; the local GBA's link traffic goes here. */
    @Volatile var backend: LinkBackend? = null; private set
    @Volatile var closed = false; private set

    /** Our addresses as written into our code. */
    val candidates: List<Candidate> get() = if (hosting) invite.candidates else own

    /**
     * Guest: no answer from the host [REPLY_PROMPT_MS] after our first HELLO, and none since. Why, we
     * can't tell (its network may only let us in once it probes our reply code, or its room is gone).
     * Set by the timer itself, so the screens it refreshes always see it.
     */
    val hostSilent: Boolean get() = !hosting && prompted && connectedVia == null && failure == null

    /**
     * Guest: our reply code holds an address the host could reach from elsewhere, so sending it can
     * help: a public IPv4 (router port, or STUN said friends can use it), or IPv6 when the host has
     * IPv6 too. LAN addresses alone can't: on the same Wi-Fi the invite works by itself.
     */
    val replyCanHelp: Boolean get() {
        if (hosting || offline || code.isEmpty()) return false
        val mine = own
        return mine.any { it.kind == Candidate.Kind.MAPPED } ||
            (mine.any { it.kind == Candidate.Kind.IPV6 } && invite.candidates.any { it.kind == Candidate.Kind.IPV6 })
    }

    /** Host: a guest got in over a LAN path (so the room is used on one Wi-Fi). */
    @Volatile var lanGuest = false; private set

    @Volatile private var prompted = false // guest: the reply prompt's timer ran
    @Volatile private var own: List<Candidate> = emptyList()
    @Volatile private var socket: DatagramSocket? = null
    private var local: List<Candidate> = emptyList() // the network's own addresses last time we looked (worker)
    // Released by close(); guarded by lock.
    private var transport: UdpTransport? = null
    private var hold: Closeable? = null
    private var mapper: PortMapper? = null
    private val watches = ArrayList<ConnectivityManager.NetworkCallback>()

    fun start() = background(fatal = true) { setUp() }

    /** What became of a pasted reply code. */
    enum class ReplyResult {
        /** The room isn't up yet. */
        NOT_READY,
        /** Its addresses are being probed: the guest's next HELLO gets in. */
        PROBING,
        /** That guest is connected already (it got in with the invite alone): nothing to do. */
        CONNECTED,
    }

    /**
     * Host: a guest's reply code arrived. Probing its addresses opens both firewalls,
     * so the guest's next HELLO gets in.
     */
    fun addReply(reply: Reply): ReplyResult {
        val t = synchronized(lock) { if (closed) null else transport } ?: return ReplyResult.NOT_READY
        if (synchronized(pipesIn) { reply.connId in pipesIn }) {
            log("host: reply code from ${reply.guestName} (${hex(reply.connId)}): connected already, nothing to probe")
            return ReplyResult.CONNECTED
        }
        log("host: reply code from ${reply.guestName} (${hex(reply.connId)}) with ${reply.candidates.size} addresses")
        t.probe(reply.connId, reply.candidates) // only queues work for the transport's thread (it logs "probing ...")
        return ReplyResult.PROBING
    }

    /** Ends everything (in the background: saying goodbye takes up to ~2 s). Safe to call twice. */
    fun close() {
        val t: UdpTransport?
        val b: LinkBackend?
        val h: Closeable?
        val m: PortMapper?
        val w: List<ConnectivityManager.NetworkCallback>
        synchronized(lock) {
            if (closed) return
            closed = true
            t = transport; b = backend; h = hold; m = mapper; w = watches.toList()
            transport = null; hold = null; mapper = null; watches.clear()
        }
        main.removeCallbacks(recheck)
        main.removeCallbacks(replyPrompt)
        for (cb in w) try { cm.unregisterNetworkCallback(cb) } catch (e: RuntimeException) {}
        worker.shutdownNow() // wakes a setup that is waiting for mobile data or the router
        Thread({
            b?.close() // the room tells its guests; a guest session leaves (and closes the transport)
            t?.close()
            m?.close()
            h?.close()
            log("link closed")
        }, "phone-link-close").start()
    }

    // ---------------------------------------------------------------- setup (worker)

    private fun setUp() {
        step("Checking your network…")
        val choice = choose()
        if (!keep { hold = choice.hold; net = choice.snap; onMobileData = choice.mobile }) return choice.hold.close()
        val snap = choice.snap
        if (snap.network == null) return fail("This phone has no network. Connect to Wi-Fi or mobile data and try again.")
        val t = try {
            UdpTransport(key, hosting, TransportOptions(
                socketHook = { s -> socket = s; bind(snap, s) },
                onGuestArrived = ::guestArrived,
                onConnected = { _, path, _, probed -> connected(path, probed) },
                log = ::log,
            ))
        } catch (e: Exception) {
            log("transport: $e")
            return fail("Couldn't open a network connection on this phone (${e.message ?: e.javaClass.simpleName}).")
        }
        if (!keep { transport = t }) return t.close()
        if (hosting) {
            val room = HostRoom(invite.roomName, invite.hostName, rom, invite.maxPlayers, roomListener(), ::log)
            if (!keep { backend = room }) return room.close()
            t.setAcceptor { connId, pipe -> room.acceptor(connId, Tracked(connId, pipe))?.let { watchJoin(connId, it) } }
            log("host: room open on port ${t.port}")
            update()
        } else {
            val guest = GuestSession(invite, myName, rom, t, connId, listener)
            if (!keep { backend = guest }) return // close() already took the transport
            update()
            log("guest: trying ${invite.hostName}'s ${invite.candidates.size} addresses as ${hex(connId)}: " +
                invite.candidates.joinToString { UdpTransport.describe(it.address, it.kind) })
            main.postDelayed(replyPrompt, REPLY_PROMPT_MS)
            guest.start() // HELLOs go out while we work out the reply code
        }
        gather(snap)
        watch()
    }

    // ---------------------------------------------------------------- how a connection got through

    /** Guest: the screens bring up the reply code once the host has been silent this long. */
    private val replyPrompt = Runnable {
        if (closed || connectedVia != null || failure != null) return@Runnable
        prompted = true
        log("guest: no answer from ${invite.hostName}'s phone after ${REPLY_PROMPT_MS / 1000} s " +
            if (replyCanHelp) "(asking for the reply code)" else "(our reply code has no address it could reach from elsewhere)")
        update()
    }

    /** Guest (transport thread): the host answered (the transport logs "pipe open via ..."). */
    private fun connected(path: String, probed: Boolean) {
        connectedVia = path
        connectedAfterReply = probed
        update()
    }

    /** Host: how one guest's connection got in, until the room has its name and we said so. */
    private class Arrival(val path: String, val afterReply: Boolean) {
        @Volatile var name: String? = null
    }

    /** By connId; a few at most (refused guests' entries are dropped oldest first). */
    private val arrivals = object : LinkedHashMap<Long, Arrival>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Arrival>) = size > 16
    }

    /** Host: the connIds whose pipe is open (a reply code pasted for one of them needs no probing). */
    private val pipesIn = HashSet<Long>()

    /** Host (transport thread): a guest's HELLO got in (the transport logs "pipe open via ..."). */
    private fun guestArrived(connId: Long, path: String, afterProbe: Boolean) {
        synchronized(arrivals) { arrivals[connId] = Arrival(path, afterProbe) }
        synchronized(pipesIn) { pipesIn += connId }
        if (UdpTransport.isLanPath(path)) lanGuest = true
    }

    /** Host: a guest's pipe as the room sees it; the room closing it (e.g. refusing the join) ends our record too. */
    private inner class Tracked(private val connId: Long, private val pipe: Pipe) : Pipe by pipe {
        override fun close(reason: String) {
            synchronized(pipesIn) { pipesIn -= connId }
            pipe.close(reason)
        }
    }

    /** Host: the room's handler for a guest's pipe, which first notes the name the guest joins with. */
    private fun watchJoin(connId: Long, room: PipeHandler) = object : PipeHandler {
        override fun onMessage(pipe: Pipe, message: ByteArray) {
            val a = synchronized(arrivals) { arrivals[connId] }
            if (a != null && a.name == null && message.isNotEmpty() && message[0] == Protocol.CONTROL) {
                (Protocol.decodeControl(message) as? Control.Join)?.let { a.name = it.name }
            }
            room.onMessage(pipe, message)
        }

        override fun onClosed(pipe: Pipe, reason: String) {
            synchronized(arrivals) { arrivals.remove(connId) }
            synchronized(pipesIn) { pipesIn -= connId }
            room.onClosed(pipe, reason)
        }
    }

    /**
     * Host: what the room tells the screens, and for every guest that becomes a player, a news line
     * saying how it got in ("Ash connected directly" / "... after you pasted their reply code").
     */
    private fun roomListener(): LinkListener = object : LinkListener by listener {
        private var names: List<String> = emptyList() // the last roster; the room calls one at a time

        override fun onJoined(roomName: String, linkId: Int, players: List<RoomPlayer>) {
            listener.onJoined(roomName, linkId, players)
            val before = names.toMutableList()
            val fresh = players.map { it.name }.filter { !before.remove(it) }
            names = players.map { it.name }
            for (name in fresh) {
                // The newest with that name: a refused guest's entry may linger (the room closes its pipe itself).
                val (connId, a) = synchronized(arrivals) {
                    arrivals.entries.lastOrNull { it.value.name == name }?.let { e -> e.key to e.value }
                        ?.also { arrivals.remove(it.first) }
                } ?: continue
                log("host: $name (${hex(connId)}) is in the room via ${a.path}, " +
                    if (a.afterReply) "after their reply code was pasted" else "with the invite only")
                listener.onStatus(if (a.afterReply) "$name connected after you pasted their reply code" else "$name connected directly")
            }
        }
    }

    private class Choice(val snap: NetEnv.Snapshot, val hold: Closeable, val mobile: Boolean)

    /**
     * The network for the link: normally the default one. A Wi-Fi without IPv6 (e.g. a
     * home line behind carrier NAT) can't be reached from other places and can't reach a
     * host's IPv6 either, so then the link moves to mobile data if that has IPv6.
     *
     * A guest doesn't try to tell whether it is on the host's Wi-Fi: a private IPv4 in the same
     * /24 proves nothing (most home routers hand out 192.168.0.x or 192.168.1.x). It moves only
     * when the invite has IPv6 and its own Wi-Fi has none; the invite's addresses all come from
     * one network, so a guest on the host's Wi-Fi would have IPv6 as well, and stays.
     */
    private fun choose(): Choice {
        val def = NetEnv.snapshot(app)
        val peers = if (hosting) null else invite.candidates
        val tryMobile = !stayOnWifi && def.network != null && def.homeRouter && !def.hasIpv6 &&
            (peers == null || peers.any { it.kind == Candidate.Kind.IPV6 })
        if (tryMobile) {
            // Already up next to Wi-Fi (Android's default): the request returns at once.
            // Otherwise Android has to bring it up, if mobile data is on at all.
            step(if (def.mobileHasIpv6) "Your Wi-Fi has no IPv6, so the link will use mobile data…"
                 else "Your Wi-Fi has no IPv6. Checking mobile data…")
            val wait = if (def.mobileHasIpv6) 8_000 else 5_000
            @Suppress("DEPRECATION") // allNetworks: only to tell "already up" from "brought up for us"
            val upBefore = cm.allNetworks.toSet()
            val got = AtomicReference<Network?>()
            val done = CountDownLatch(1)
            val h = NetEnv.requestCellular(app, wait) { n -> got.set(n); done.countDown() }
            val cell = try {
                done.await(wait + 1_000L, TimeUnit.MILLISECONDS)
                got.get()?.let { cellular(it, fresh = it !in upBefore) }
            } catch (e: Throwable) { // InterruptedException: closed while waiting
                h.close()
                throw e
            }
            if (cell?.network != null && cell.hasIpv6) {
                log("network: link on mobile data, as ${def.label} has no IPv6: ${cell.describe()}")
                return Choice(cell, h, true)
            }
            h.close()
            log("network: mobile data ${if (cell?.network == null) "not available" else "has no IPv6 either"}")
        }
        log("network: link on ${def.describe()}")
        return Choice(def, def.keepAlive(app), false)
    }

    /**
     * Mobile data that Android brought up just now: its first addresses often lack the global
     * IPv6 one, which follows a moment later (router advertisement, then duplicate address
     * detection while it is tentative). Give it a few seconds before deciding there is none.
     * Mobile data that was already up has its addresses: look once.
     */
    private fun cellular(network: Network, fresh: Boolean): NetEnv.Snapshot {
        val until = System.nanoTime() + if (fresh) CELL_IPV6_WAIT_NS else 0L
        while (true) {
            val s = NetEnv.snapshot(app, network)
            if (s.network == null || s.hasIpv6 || System.nanoTime() >= until) return s
            Thread.sleep(250)
        }
    }

    private fun bind(snap: NetEnv.Snapshot, s: DatagramSocket) {
        try {
            snap.socketHook(s)
        } catch (e: Exception) {
            log("bind to ${snap.label}: $e") // stays on the default network; timeouts report a broken link
        }
    }

    /**
     * Our addresses for the code: the network's own (on mobile data without its private IPv4, or an
     * IPv4 that only looks public: NetEnv.Snapshot.candidates), a router port mapping, and, if the user
     * allowed STUN, our public IPv4 when the two-server self-test says friends can use it.
     */
    private fun gather(snap: NetEnv.Snapshot) {
        val t = synchronized(lock) { transport } ?: return
        val mine = snap.candidates(t.port)
        local = mine
        if (!hosting) publish(mine) // the reply code can go out before the router answers
        // A mapping for this port may still be there: let the router forget it first, or its
        // late release would delete the new one.
        synchronized(lock) { mapper.also { mapper = null } }?.let { it.close(); it.awaitReleased(1500) }
        var extra = emptyList<Candidate>()
        routerPort = null
        val m = snap.portMapper(t.port, ::log)
        if (m != null) {
            if (!keep { mapper = m }) return m.close()
            step("Asking your router to open a port…")
            extra = m.map()
            routerPort = extra.isNotEmpty()
            log(m.describe())
        }
        nat = null
        var own = mine
        if (askStun) {
            step("Checking your network's IPv4 with STUN…")
            val check = natCheck(t, snap)
            nat = check
            own = snap.candidates(t.port, check) // our public IPv4, if friends can use it
        }
        if (!closed) publish(own + extra)
    }

    /**
     * The NAT self-test (only when the user allowed STUN): two STUN servers on different IPs, asked at
     * once from the room's own socket. Both seeing the same public port means friends can use it
     * ("easy"); different ports mean the NAT makes a new one per destination ("strict"), and only IPv6
     * will get a friend through. Blocks for at most ~2 s.
     */
    private fun natCheck(t: UdpTransport, snap: NetEnv.Snapshot): NatCheck {
        val answers = arrayOfNulls<InetSocketAddress>(STUN_SERVERS.size)
        val asked = arrayOfNulls<InetAddress>(STUN_SERVERS.size)
        val lookups = STUN_SERVERS.mapIndexed { i, (host, port) ->
            thread(isDaemon = true, name = "stun-$i") {
                try {
                    val addrs = snap.network?.getAllByName(host) ?: InetAddress.getAllByName(host)
                    val v4 = addrs.firstOrNull { it is Inet4Address } ?: return@thread
                    asked[i] = v4
                    answers[i] = t.stun(InetSocketAddress(v4, port), 2000)
                } catch (e: Exception) {
                    log("stun $host: $e")
                }
            }
        }
        lookups.forEach { it.join() } // InterruptedException: the link closed meanwhile
        // One IP behind both names would be one server asked twice: nothing to compare.
        if (asked[0] != null && asked[0] == asked[1]) answers[1] = null
        val check = NatCheck(snap.publicV4.map { InetSocketAddress(it, t.port) }, answers.toList())
        log("nat self-test: ${check.describe()} [" + STUN_SERVERS.indices.joinToString { i ->
            "${STUN_SERVERS[i].first} (${asked[i]?.hostAddress ?: "not found"}) saw ${answers[i]?.let(UdpTransport::format) ?: "nothing"}"
        } + "]")
        return check
    }

    private fun publish(list: List<Candidate>) {
        val cands = list.distinct().take(Codes.MAX_CANDIDATES)
        if (hosting) {
            val old = invite
            val first = code.isEmpty()
            if (first || cands.toSet() != old.candidates.toSet()) {
                // Same room id and secret: an older invite still works wherever its addresses do.
                val inv = Invite(old.roomId, old.secret, old.romSha1Prefix, old.maxPlayers, old.hostName, old.roomName, cands)
                invite = inv
                code = inv.encode()
                if (!first) inviteVersion++
                log("host: invite v$inviteVersion for room ${hex(inv.roomId)} (${cands.size} addresses: " +
                    "${cands.joinToString { UdpTransport.describe(it.address, it.kind) }})")
            }
        } else {
            val first = code.isEmpty()
            if (first || cands.toSet() != own.toSet()) {
                own = cands
                code = Codes.reply(key, connId, myName, cands)
                if (!first) replyVersion++ // a reply already sent lacks these addresses
                log("guest: reply code v$replyVersion for ${hex(connId)} (${cands.size} addresses: " +
                    "${cands.joinToString { UdpTransport.describe(it.address, it.kind) }})")
            }
        }
        progress = ""
        update()
    }

    // ---------------------------------------------------------------- network changes

    /** Watches every network (addresses come and go) and the default one (Wi-Fi in or out of range). */
    private fun watch() {
        fun callback() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = poke()
            override fun onLost(network: Network) = poke()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = poke()
        }
        val all = callback()
        val dflt = callback()
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        synchronized(lock) { // so close() can't miss a callback registered just now
            if (closed) return
            try {
                cm.registerNetworkCallback(request, all)
                watches += all
                cm.registerDefaultNetworkCallback(dflt)
                watches += dflt
            } catch (e: RuntimeException) {
                log("network watch: $e")
            }
        }
    }

    // Network events come in bursts (and once for every network when the watch starts):
    // look once things have settled.
    private fun poke() {
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, 2000)
    }

    private val recheck = Runnable {
        if (!closed) try { background(fatal = false) { recheckNow() } } catch (e: RejectedExecutionException) {}
    }

    private fun recheckNow() {
        val cur = net ?: return
        val t = synchronized(lock) { transport } ?: return
        val now = cur.network?.let { NetEnv.snapshot(app, it) }
        if (now?.network != null) {
            // Still on the same network; did its addresses change (new IPv6 prefix, DHCP)?
            if (now.candidates(t.port) == local) return
            log("network: addresses changed: ${now.describe()}")
            net = now
            gather(now)
            return
        }
        // Our network is gone (Wi-Fi out of range, mobile data off): move the socket to the best
        // one left. Peers see our packets come from a new address and follow if their firewall lets them.
        log("network: ${cur.label} is gone, moving the link")
        val c = choose()
        val old: Closeable?
        synchronized(lock) {
            if (closed) {
                c.hold.close()
                return
            }
            old = hold
            hold = c.hold
            net = c.snap
            onMobileData = c.mobile
        }
        old?.close()
        val s = socket
        if (s != null && c.snap.network != null) bind(c.snap, s)
        if (c.snap.network == null) {
            local = emptyList()
            update() // no network at all; the next change brings us back here
            return
        }
        gather(c.snap)
    }

    // ---------------------------------------------------------------- helpers

    /** Runs [block] on the worker. A failure ends the link only during setup ([fatal]); a running room carries on. */
    private fun background(fatal: Boolean, block: () -> Unit) = worker.execute {
        try {
            block()
        } catch (e: InterruptedException) {
            // closed while waiting
        } catch (e: Exception) {
            log("link ${if (fatal) "setup" else "update"}: $e")
            if (fatal) fail("Something went wrong with the connection (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /** Runs [block] (storing a resource) unless the link closed meanwhile; then the caller releases it. */
    private inline fun keep(block: () -> Unit): Boolean = synchronized(lock) {
        if (closed) false else {
            block()
            true
        }
    }

    private fun update() {
        main.post { if (!closed) onUpdate(this) }
    }

    private fun step(text: String) {
        progress = text
        update()
    }

    private fun fail(message: String) {
        if (closed) return
        log("link failed: $message")
        failure = message
        update()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private fun hex(id: Long) = java.lang.Long.toHexString(id)

    companion object {
        /** logcat tag of everything link play logs: `adb logcat -s LinkPlay`. */
        const val TAG = "LinkPlay"
        /** The NAT self-test asks both, at once, from the room's socket: two companies, two IPs. */
        private val STUN_SERVERS = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478)
        private const val CELL_IPV6_WAIT_NS = 3_000_000_000L
        /** Guest: no answer from the host this long after the first HELLO means it needs our reply code. */
        const val REPLY_PROMPT_MS = 5_000L
    }
}
