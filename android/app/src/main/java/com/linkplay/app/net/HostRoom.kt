package com.linkplay.app.net

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * A link room hosted on this phone: the same rooms + NetLink sequencer as
 * server/relay.js, with guests reaching it over [Pipe]s instead of WebSockets.
 * The host's own GBA is always player 1 (linkId 0) and talks to the room
 * through [sendLink] / [local], so every GBA, the host's included, sees the
 * exact same ordered stream of link frames.
 *
 * Pass [acceptor] to Transport.setAcceptor. A connection becomes a player only
 * after a valid join (same protocol, same full ROM SHA-1, a free slot).
 * The sequencer starts with the room and [local] gets onJoined right away.
 * Once [close] returns, [local] hears nothing more from the room (close waits
 * for a callback that is already running, so callbacks must not wait for the
 * thread that calls close).
 */
class HostRoom(
    roomName: String,
    hostName: String,
    private val rom: RomId,
    maxPlayers: Int,
    private val local: LinkListener,
    private val log: (String) -> Unit = {},
) : LinkBackend {
    val maxPlayers = maxPlayers.coerceIn(2, Protocol.MAX_PLAYERS)
    // Bounded like every name, so a welcome always fits in one pipe message.
    val roomName = Protocol.clean(roomName, Protocol.MAX_ROOM_NAME).ifEmpty { "Link room" }

    /** A player; [conn] is null for the host's own GBA. */
    private class Member(val name: String, val conn: Conn?)

    private inner class Conn(val connId: Long, val pipe: Pipe, val since: Long) : PipeHandler {
        var member: Member? = null
        var refusedAt: Long? = null

        override fun onMessage(pipe: Pipe, message: ByteArray) = update { received(this, message) }
        override fun onClosed(pipe: Pipe, reason: String) = update { closed(this, reason) }
    }

    private val lock = Any()
    private val members = ArrayList<Member>() // index == linkId, kept contiguous like relay.js
    private val conns = LinkedHashSet<Conn>() // joined or not
    private val seq = Sequencer(System.nanoTime())
    @Volatile private var closed = false

    // Everything that leaves the room (pipe sends, local callbacks) is queued in
    // lock order and run outside the lock by one thread at a time. So every
    // receiver sees one order, a listener calling back into the room from a
    // callback only queues more (never nests), and a transport that holds its
    // own lock while calling us can't deadlock with us.
    private val outbox = ConcurrentLinkedQueue<() -> Unit>()
    private val draining = AtomicBoolean()
    // Held while a callback to [local] runs, so close() can wait for it (see postLocal).
    private val localLock = Any()

    private val thread = Thread(::sequencerLoop, "link-room").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 2 // late ticks are late link frames on every GBA
    }

    val players: List<RoomPlayer> get() = synchronized(lock) { roster() }

    /** For tests: the sequencer thread stops after [close]. */
    internal val running get() = thread.isAlive

    init {
        synchronized(lock) {
            members.add(Member(Protocol.cleanName(hostName), null))
            // The host is player 1 from the start: queued before anything else can happen (the
            // sequencer's first flush delivers it) and, like every local callback, dropped if
            // close() comes first, e.g. when the UI gives up right after creating the room.
            sendRoster(null)
        }
        thread.start()
    }

    /**
     * What the UI passes to Transport.setAcceptor. Null refuses the connection (room closed, or
     * too many connections). A full room still takes the pipe so its join can be answered with
     * room_full: the guest then sees "Room is full." instead of a bare transport refusal.
     */
    fun acceptor(connId: Long, pipe: Pipe): PipeHandler? = update {
        if (closed || conns.size >= MAX_CONNS) {
            log("room: refused a connection from ${pipe.path} (${if (closed) "room closed" else "too many connections"})")
            null
        } else {
            log("room: connection from ${pipe.path}")
            Conn(connId, pipe, System.nanoTime()).also { conns.add(it) }
        }
    }

    /** Link traffic from the host's GBA: u8 kind, u8 0, u16le words[1 | 8]. */
    override fun sendLink(payload: ByteArray) {
        update {
            if (!closed) gbaMessage(0, payload, 0, 2)
        }
    }

    /** The host leaving ends the room for everyone. */
    override fun leave() = close()

    override fun close() = close("The host closed the room.")

    fun close(reason: String) {
        update {
            if (closed) return@update
            closed = true
            // Bounded like the guest's own decode, so the message always fits in a pipe message
            // (a reason may embed an exception's text).
            val why = Protocol.clean(reason, MAX_REASON).ifEmpty { "Room closed." }
            val bye = Protocol.encode(Control.Closed(why))
            val pipes = conns.map { it.pipe }
            conns.clear()
            members.subList(1, members.size).clear()
            log("room: \"$roomName\" closed ($why)")
            // One action per pipe operation: one failing pipe must not keep the others open.
            for (p in pipes) {
                post { p.send(bye) }
                post { p.close(why) }
            }
        }
        synchronized(localLock) {} // a callback to [local] already running finishes first
        LockSupport.unpark(thread)
    }

    // ---------------------------------------------------------------- sequencer thread

    private fun sequencerLoop() {
        while (!closed) {
            val wait = update {
                if (closed) return@update 0L
                val now = System.nanoTime()
                seq.pump(now) { broadcast(Protocol.KIND_ENTRY, 0, it) }
                housekeeping(now)
                if (seq.hasQueued()) seq.nextTick - System.nanoTime() else IDLE_NS
            }
            if (wait > 0) LockSupport.parkNanos(this, wait)
        }
    }

    private fun housekeeping(now: Long) {
        for (c in conns.toList()) {
            val refused = c.refusedAt
            when {
                // Give a refused guest time to read why before hanging up.
                refused != null && now - refused > LINGER_NS -> drop(c, "refused")
                refused == null && c.member == null && now - c.since > JOIN_TIMEOUT_NS ->
                    refuse(c, "bad_request", "The guest never asked to join.")
            }
        }
    }

    // ---------------------------------------------------------------- room logic (under lock)

    private fun received(conn: Conn, msg: ByteArray) {
        if (closed || conn !in conns || msg.isEmpty()) return
        when (msg[0]) {
            Protocol.LINK_UP -> {
                val linkId = members.indexOf(conn.member ?: return)
                gbaMessage(linkId, msg, 1, 2)
            }
            Protocol.CONTROL -> when (val c = Protocol.decodeControl(msg)) {
                is Control.Join -> join(conn, c)
                Control.Leave -> {
                    conn.member?.let { remove(it, "left") }
                    drop(conn, "left")
                }
                else -> {} // unknown or malformed: ignored, like relay.js
            }
        }
    }

    private fun closed(conn: Conn, reason: String) {
        if (!conns.remove(conn)) return
        val m = conn.member
        if (m != null) remove(m, "connection lost: $reason") else log("room: ${conn.pipe.path} went away before joining ($reason)")
    }

    /** A handshake or command from [linkId]'s GBA; the kind byte is at [kindAt], words from [wordsAt]. */
    private fun gbaMessage(linkId: Int, msg: ByteArray, kindAt: Int, wordsAt: Int) {
        when (Protocol.wordCount(msg, kindAt, wordsAt)) {
            1 -> broadcast(Protocol.KIND_HANDSHAKE, linkId, msg.copyOfRange(wordsAt, wordsAt + 2))
            Protocol.CMD_WORDS -> {
                seq.onCommand(linkId, IntArray(Protocol.CMD_WORDS) { Protocol.u16(msg, wordsAt + it * 2) })
                // Out at once if a frame has passed since the last entry; otherwise the sequencer
                // thread is woken to send it when the spacing allows.
                seq.pump(System.nanoTime()) { broadcast(Protocol.KIND_ENTRY, 0, it) }
                if (seq.hasQueued()) LockSupport.unpark(thread)
            }
        }
    }

    private fun join(conn: Conn, j: Control.Join) {
        if (conn.member != null || conn.refusedAt != null) return
        val theirs = j.rom
        when {
            j.proto != Protocol.VERSION -> refuse(conn, "proto",
                "The two phones have different Link Play versions (room protocol ${j.proto} vs ${Protocol.VERSION}). Update both apps.")
            theirs == null || !SHA1.matches(theirs.sha1) || theirs.sha1 != rom.sha1.lowercase() -> refuse(conn, "rom_mismatch",
                "This room plays \"${rom.title}\" (${rom.sha1.take(12)}). Your ROM is different, so you can't join.")
            members.size >= maxPlayers -> refuse(conn, "room_full", "Room is full.")
            else -> {
                val m = Member(j.name, conn)
                members.add(m)
                conn.member = m
                log("room: ${m.name} joined \"$roomName\" as player ${members.size} via ${conn.pipe.path}")
                postLocal { local.onStatus("${m.name} joined the room") }
                sendRoster(m)
            }
        }
    }

    private fun refuse(conn: Conn, code: String, message: String) {
        conn.refusedAt = System.nanoTime()
        log("room: refused ${conn.pipe.path}: $code")
        val msg = Protocol.encode(Control.Error(code, Protocol.clean(message, MAX_REASON))) // the ROM title is in it
        post { conn.pipe.send(msg) }
    }

    private fun drop(conn: Conn, reason: String) {
        if (conns.remove(conn)) post { conn.pipe.close(reason) }
    }

    // The player is gone for good: unplug their GBA for everyone, then renumber
    // so link ids stay contiguous (the game's handshake counts players 1..n).
    private fun remove(member: Member, reason: String) {
        val gone = members.indexOf(member)
        if (gone <= 0) return // not a player (the host never leaves; it closes the room)
        broadcast(Protocol.KIND_LEFT, gone, ByteArray(0))
        members.removeAt(gone)
        seq.remove(gone)
        member.conn?.member = null
        log("room: ${member.name} left \"$roomName\" ($reason)")
        postLocal { local.onPartnerLeft(member.name) }
        sendRoster(null)
    }

    private fun roster() = members.mapIndexed { id, m -> RoomPlayer(id, m.name, true) }

    /** Everyone learns the new roster (and their possibly new linkId); [welcome] gets it as its welcome. */
    private fun sendRoster(welcome: Member?) {
        val players = roster()
        members.forEachIndexed { id, m ->
            val conn = m.conn ?: return@forEachIndexed
            val msg = Protocol.encode(if (m === welcome) Control.Welcome(roomName, id, players) else Control.Roster(id, players))
            post { conn.pipe.send(msg) }
        }
        postLocal { local.onJoined(roomName, 0, players) }
    }

    private fun broadcast(kind: Int, player: Int, payload: ByteArray) {
        val frame = ByteArray(2 + payload.size)
        frame[0] = kind.toByte()
        frame[1] = player.toByte()
        payload.copyInto(frame, 2)
        val msg = Protocol.linkDown(frame)
        // Separate actions: a pipe whose send fails must not cost the others (or the host's
        // GBA) this frame, or their streams would differ.
        for (m in members) m.conn?.pipe?.let { p -> post { p.send(msg) } }
        postLocal { local.onLinkFrame(frame) }
    }

    // ---------------------------------------------------------------- outbox

    private fun post(action: () -> Unit) {
        outbox.add(action)
    }

    /** A callback to [local]; skipped once the room is closed (close() waits for a running one, see localLock). */
    private fun postLocal(callback: () -> Unit) = post {
        synchronized(localLock) {
            if (!closed) callback()
        }
    }

    private inline fun <T> update(block: () -> T): T {
        val r = synchronized(lock) { block() }
        flush()
        return r
    }

    /** Runs queued actions in order; one thread at a time, the others just leave theirs in the queue. */
    private fun flush() {
        while (outbox.isNotEmpty() && draining.compareAndSet(false, true)) {
            try {
                while (true) {
                    val action = outbox.poll() ?: break
                    try {
                        action()
                    } catch (e: Exception) {
                        log("room: callback failed: $e")
                    }
                }
            } finally {
                draining.set(false)
            }
        }
    }

    companion object {
        private const val MAX_CONNS = 8 // joined or still joining
        private const val IDLE_NS = 250_000_000L // housekeeping cadence when nothing is queued
        private const val LINGER_NS = 2_000_000_000L
        private const val JOIN_TIMEOUT_NS = 30_000_000_000L
        private const val MAX_REASON = 300 // what GuestSession keeps of an error / closed text
        private val SHA1 = Regex("^[0-9a-f]{40}$")
    }
}

/**
 * The NetLink sequencer of server/relay.js (Room.onCommand / Room.pump): one
 * entry per GBA frame with at most one command per player, in arrival order.
 * Not thread-safe; HostRoom calls it under its lock. Times are System.nanoTime().
 */
internal class Sequencer(var nextTick: Long) { // nextTick: the earliest time the next entry may go out
    private val fifo = Array(Protocol.MAX_PLAYERS) { ArrayDeque<IntArray>() }

    fun queued(player: Int) = fifo[player].size

    fun onCommand(player: Int, words: IntArray) {
        val q = fifo[player]
        if (q.size >= 2 && stateReplaceable(q.last(), words)) {
            q[q.size - 1] = words // backed up: newer position replaces the queued one
        } else if (q.size < FIFO_LIMIT) {
            q.addLast(words)
        }
    }

    fun hasQueued() = fifo.any { it.isNotEmpty() }

    /**
     * Emits the 64-byte entry that is due at [now], if any: as soon as something is queued and at
     * least FRAME_NS have passed since the last entry, otherwise [nextTick] (= last + FRAME_NS)
     * is when it may go. Entries average one per FRAME_NS at most (never a burst), the rate of
     * relay.js's fixed tick minus the wait for the tick.
     */
    fun pump(now: Long, emit: (ByteArray) -> Unit) {
        if (now - nextTick < 0 || !hasQueued()) return
        // Slightly late (a late wake-up) keeps the frame grid, so jitter doesn't slow the stream;
        // after an idle spell the next entry is a full frame away.
        // Never closer than half a frame to the previous entry, so a late wake can't make a burst.
        nextTick = if (now - nextTick < FRAME_NS) maxOf(nextTick + FRAME_NS, now + FRAME_NS / 2) else now + FRAME_NS
        val entry = ByteArray(Protocol.ENTRY_BYTES)
        for (p in 0 until Protocol.MAX_PLAYERS) {
            val cmd = fifo[p].removeFirstOrNull() ?: continue
            for (w in 0 until Protocol.CMD_WORDS) {
                val at = (p * Protocol.CMD_WORDS + w) * 2
                entry[at] = cmd[w].toByte()
                entry[at + 1] = (cmd[w] shr 8).toByte()
            }
        }
        emit(entry)
    }

    /** Player [gone] left: their queue goes, everyone above moves down a slot with theirs. */
    fun remove(gone: Int) {
        for (p in gone until Protocol.MAX_PLAYERS - 1) fifo[p] = fifo[p + 1]
        fifo[Protocol.MAX_PLAYERS - 1] = ArrayDeque()
    }

    companion object {
        const val FIFO_LIMIT = 600 // queued commands per player before dropping
        const val STATE_CMD = 0xBEBE // Quetzal's per-frame overworld state packet

        // A newer state packet may replace an older one only if nothing but
        // position / facing / animation changed (flags such as "wants to battle" survive).
        fun stateReplaceable(a: IntArray, b: IntArray) =
            a[0] == STATE_CMD && b[0] == STATE_CMD &&
                a[1] == b[1] && a[5] == b[5] && a[7] == b[7] && (a[6] and 0xff00) == (b[6] and 0xff00)
    }
}
