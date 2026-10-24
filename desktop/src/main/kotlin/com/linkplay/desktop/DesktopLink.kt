package com.linkplay.desktop

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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread

/**
 * Link play with nothing but computers and phones: a room hosted on this computer ([hosting]) or our
 * place in a friend's, found through invite / reply codes. The desktop twin of the phone app's
 * PhoneLink, and it behaves the same: it owns everything that must be given back when it ends (the
 * UDP transport, the room or guest session, the router port mapping and the network watch).
 *
 * Network work runs on its own thread ("phone-link", named like the phone's so logs compare).
 * [onUpdate] runs on the Swing event thread whenever something the screens show changed (code,
 * reachability, progress, failure); the room's own events go straight from the backend to [listener],
 * plus (host) a news line per guest saying whether it got in with the invite alone or only after its
 * reply code was pasted.
 *
 * Differences from the phone: no mobile-data switching or "stay on Wi-Fi" (a computer has one route
 * out), no network callbacks (a watcher compares the addresses every three seconds instead), and
 * [awaitClosed], because on a computer the process may exit right after [close] and the goodbye
 * to the room and the router must still go out.
 *
 * Everything worth knowing after a real test goes to the app's log (log.txt in the data folder, and
 * standard error): the network chosen, the NAT self-test, "probing <connId> -> ...", "<connId> PROBE from
 * <address>", "<connId> pipe open via <path> (invite only|after reply code)", "host: <name> (<connId>) is in
 * the room via ...", and strangers that were limited. Never a code: an invite holds the room's secret, so
 * the log names the room id (host) or connection id (guest) and the code's version instead.
 */
class DesktopLink(
    val hosting: Boolean,
    /** Host: a fresh Codes.newInvite (its addresses are filled in here). Guest: the invite being joined. */
    invite: Invite,
    private val myName: String,
    private val rom: RomId,
    /** The user allowed asking a public STUN server for our public IPv4 (Advanced, off by default). */
    private val askStun: Boolean,
    private val listener: LinkListener,
    private val logSink: (String) -> Unit = { Log.i(it) },
    /** Runs on the Swing event thread, only while the link is open. */
    private val onUpdate: (DesktopLink) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-link").apply { isDaemon = true } }
    private val lock = Any()

    val key = invite.key
    /** Guest: our connection id; the host finds it in our reply code. */
    val connId = Codes.newConnId()

    // What the screens show. Written on the worker, read on the event thread after onUpdate.
    /** Host: the invite to share, remade when our addresses change. Guest: the invite we're joining. */
    @Volatile var invite = invite; private set
    /** Host: the invite text. Guest: the reply text. Empty until our addresses are known. */
    @Volatile var code = ""; private set
    /** Bumped each time the host's invite is remade after a network change. */
    @Volatile var inviteVersion = 0; private set
    /** Bumped each time the guest's reply code changes after it was first shown (router port, network change). */
    @Volatile var replyVersion = 0; private set
    /** The network the link runs on. */
    @Volatile var net: DesktopNet.Snapshot? = null; private set
    /** The link's network is gone and no other is up: [code] holds addresses that no longer work. */
    val offline: Boolean get() = net?.online != true
    /** Kept so the phone's screen text compiles: a computer is never on mobile data. */
    val onMobileData: Boolean get() = false
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
    @Volatile private var printSeen = "" // the network watcher's last look (DesktopNet.fingerprint)
    private var local: List<Candidate> = emptyList() // the network's own addresses last time we looked (worker)
    // Released by close(); guarded by lock.
    private var transport: UdpTransport? = null
    private var mapper: PortMapper? = null
    private var watcher: ScheduledExecutorService? = null
    /** The goodbye thread (see close), so [awaitClosed] can wait for it. */
    @Volatile private var goodbye: Thread? = null

    // Swing timers: they fire on the event thread, like the phone's main-thread Handler callbacks.
    private val replyPromptTimer = Timer(REPLY_PROMPT_MS.toInt()) { replyPrompt() }.apply { isRepeats = false }
    private val recheckTimer = Timer(RECHECK_DELAY_MS) { recheck() }.apply { isRepeats = false }

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
        synchronized(lock) {
            if (closed) return
            closed = true
            val t = transport
            val b = backend
            val m = mapper
            val w = watcher
            transport = null; mapper = null; watcher = null
            replyPromptTimer.stop()
            recheckTimer.stop()
            w?.shutdownNow()
            worker.shutdownNow() // wakes a setup that is waiting for the router or STUN
            val bye = Thread({
                b?.close() // the room tells its guests; a guest session leaves (and closes the transport)
                t?.close()
                m?.close()
                m?.awaitReleased(1500) // the router gets its port back before the process may exit
                log("link closed")
            }, "phone-link-close")
            goodbye = bye
            bye.start()
        }
    }

    /**
     * Waits until the goodbyes of [close] are out: the room's or guest's BYE, and the router's port given back.
     * Call it before the process exits (a quit that does not wait leaves friends to find out by timeout, 20 s,
     * and the router holding the port mapping for up to an hour). False if the link is still open or the wait ran out.
     */
    fun awaitClosed(timeoutMs: Long): Boolean {
        if (!closed) return false
        val bye = goodbye ?: return true
        // join(0) would wait forever: a spent deadline still gets 1 ms.
        try { bye.join(timeoutMs.coerceAtLeast(1L)) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        return !bye.isAlive
    }

    // ---------------------------------------------------------------- setup (worker)

    private fun setUp() {
        step("Checking your network…")
        printSeen = DesktopNet.fingerprint() // before the snapshot: a change while it runs shows up at the first look
        val snap = DesktopNet.snapshot()
        if (!keep { net = snap }) return
        if (!snap.online) return fail("This computer has no network. Connect to Wi-Fi or Ethernet and try again.")
        log("network: link on ${snap.describe()}")
        val t = try {
            UdpTransport(key, hosting, TransportOptions(
                onGuestArrived = ::guestArrived,
                onConnected = { _, path, _, probed -> connected(path, probed) },
                log = ::log,
            ))
        } catch (e: Exception) {
            log("transport: $e")
            return fail("Couldn't open a network connection on this computer (${e.message ?: e.javaClass.simpleName}).")
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
            replyPromptTimer.start()
            guest.start() // HELLOs go out while we work out the reply code
        }
        gather(snap)
        watch()
    }

    // ---------------------------------------------------------------- how a connection got through

    /** Guest: the screens bring up the reply code once the host has been silent this long (event thread). */
    private fun replyPrompt() {
        if (closed || connectedVia != null || failure != null) return
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

    /**
     * Our addresses for the code: the network's own (DesktopNet.Snapshot.candidates), a router port
     * mapping, and, if the user allowed STUN, our public IPv4 when the two-server self-test says
     * friends can use it.
     */
    private fun gather(snap: DesktopNet.Snapshot) {
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
     * will get a friend through. Blocks for at most ~2.5 s, however slow the name lookup is (a server that
     * has not answered by then counts as "saw nothing").
     */
    private fun natCheck(t: UdpTransport, snap: DesktopNet.Snapshot): NatCheck {
        val answers = arrayOfNulls<InetSocketAddress>(STUN_SERVERS.size)
        val asked = arrayOfNulls<InetAddress>(STUN_SERVERS.size)
        val lookups = STUN_SERVERS.mapIndexed { i, (host, port) ->
            thread(isDaemon = true, name = "stun-$i") {
                try {
                    val v4 = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } ?: return@thread
                    asked[i] = v4
                    answers[i] = t.stun(InetSocketAddress(v4, port), 2000)
                } catch (e: Exception) {
                    log("stun $host: $e")
                }
            }
        }
        // One deadline for both: a resolver that hangs (the lookup has no timeout of its own) must not hold the
        // room back. The threads are daemons; a late answer lands in an array nobody reads any more.
        val end = System.nanoTime() + 2_500_000_000L
        lookups.forEach { it.join(((end - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)) } // InterruptedException: the link closed meanwhile
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

    /**
     * Watches the network (addresses come and go: Wi-Fi in or out of range, a cable, a VPN, sleep and wake).
     * A computer has no callbacks for that in plain Java, so a daemon thread compares a cheap summary of
     * the addresses every few seconds and pokes the recheck when it changed.
     */
    private fun watch() {
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "link-net-watch").apply { isDaemon = true } }
        if (!keep { watcher = ex }) { // closed meanwhile
            ex.shutdownNow()
            return
        }
        ex.scheduleWithFixedDelay({
            try {
                val now = DesktopNet.fingerprint()
                if (now != printSeen) {
                    printSeen = now
                    poke()
                }
            } catch (e: Exception) {
                log("network watch: $e") // an exception would end the schedule: keep looking
            }
        }, WATCH_MS, WATCH_MS, TimeUnit.MILLISECONDS)
    }

    // Network changes come in bursts (an interface comes up, then its address, then the route):
    // look once things have settled.
    private fun poke() {
        if (!closed) recheckTimer.restart()
    }

    /** The settle timer fired (event thread). */
    private fun recheck() {
        if (!closed) try { background(fatal = false) { recheckNow() } } catch (e: RejectedExecutionException) {}
    }

    private fun recheckNow() {
        val t = synchronized(lock) { transport } ?: return
        val before = net
        val now = DesktopNet.snapshot()
        net = now
        if (!now.online) {
            // Our network is gone (cable out, Wi-Fi off): the next change brings us back here.
            if (before?.online == true) log("network: ${before.label} is gone")
            local = emptyList()
            update()
            return
        }
        // No socket to move: the wildcard socket follows the system's route, and peers follow our new source
        // address (as on the phone). Did the addresses change (new IPv6 prefix, DHCP, another interface)?
        if (before?.online == true && now.candidates(t.port) == local) return
        log("network: addresses changed: ${now.describe()}")
        gather(now)
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
        SwingUtilities.invokeLater { if (!closed) onUpdate(this) }
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

    private fun log(msg: String) = logSink(msg)

    private fun hex(id: Long) = java.lang.Long.toHexString(id)

    companion object {
        /** The NAT self-test asks both, at once, from the room's socket: two companies, two IPs. */
        private val STUN_SERVERS = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478)
        /** Guest: no answer from the host this long after the first HELLO means it needs our reply code. */
        const val REPLY_PROMPT_MS = 5_000L
        /** How often the watcher looks at the network. */
        private const val WATCH_MS = 3_000L
        /** Network events come in bursts: the recheck waits this long after the last one. */
        private const val RECHECK_DELAY_MS = 2_000
    }
}
