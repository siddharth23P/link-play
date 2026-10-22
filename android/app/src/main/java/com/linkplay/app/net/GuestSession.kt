package com.linkplay.app.net

import java.util.WeakHashMap

/**
 * A guest's side of a phone-hosted room: connects to the host from an invite,
 * joins, and shuttles link traffic between the local GBA and the host's
 * sequencer. Renumbering after someone leaves arrives as a roster, which is
 * reported through onJoined with the new linkId.
 *
 * Problems before we're in the room (wrong ROM, room full, host unreachable)
 * end in onFatal; losing the room afterwards ends in onSessionLost.
 * [close] also closes [transport], which belongs to this one room.
 *
 * [connId] names one attempt: a retry needs a new one (Codes.newConnId), and so a
 * new reply code. UdpTransport keeps every finished connId so late packets get a
 * BYE, and silently ignores a second connect with it; reusing one here ends in
 * onFatal at once instead of "Connecting…" forever.
 */
class GuestSession(
    private val invite: Invite,
    myName: String,
    private val rom: RomId,
    private val transport: Transport,
    private val connId: Long,
    private val listener: LinkListener,
) : LinkBackend {
    private val name = Protocol.cleanName(myName)
    private val lock = Any()
    @Volatile private var pipe: Pipe? = null
    @Volatile private var joined = false
    @Volatile private var done = false
    private var started = false
    // Touched only on the transport thread.
    private var roomName = invite.roomName
    private var roster: List<RoomPlayer> = emptyList()

    private val handler = object : PipeHandler {
        override fun onMessage(pipe: Pipe, message: ByteArray) = received(pipe, message)

        override fun onClosed(pipe: Pipe, reason: String) {
            if (!finish()) return
            if (joined) listener.onSessionLost(reason) else listener.onFatal("Couldn't reach ${invite.hostName}'s phone: $reason")
        }
    }

    fun start() {
        synchronized(lock) {
            if (started || done) return
            started = true
        }
        if (!invite.romMatches(rom.sha1)) {
            done = true
            listener.onFatal("This invite is for a different ROM than yours, so you can't join. Both phones need the same game file.")
            return
        }
        if (!claim(transport, connId)) {
            done = true
            listener.onFatal("This connection attempt was already used. Paste the invite again to start over (you'll get a new reply code).")
            return
        }
        listener.onStatus("Connecting to ${invite.hostName}'s phone…")
        transport.connect(connId, invite.candidates, handler) { opened(it) }
    }

    /** Link traffic from the local GBA (u8 kind, u8 0, u16le words[1 | 8]); dropped until we're in the room. */
    override fun sendLink(payload: ByteArray) {
        val p = pipe
        if (!joined || done || p == null) return
        Protocol.linkUp(payload)?.let(p::send)
    }

    override fun leave() {
        val p = synchronized(lock) {
            if (done) return
            done = true
            pipe
        } ?: return
        p.send(Protocol.encode(Control.Leave))
        p.close("left")
    }

    override fun close() {
        leave()
        transport.close()
    }

    private fun opened(p: Pipe) {
        synchronized(lock) {
            if (done) {
                p.close("left")
                return
            }
            pipe = p
        }
        listener.onStatus("Connected (${p.path}), joining…")
        p.send(Protocol.encode(Control.Join(name, rom, Protocol.VERSION)))
    }

    private fun received(p: Pipe, msg: ByteArray) {
        if (done || msg.isEmpty()) return
        when (msg[0]) {
            Protocol.LINK_DOWN -> {
                if (!joined) return
                val frame = msg.copyOfRange(1, msg.size)
                if (!Protocol.validFrame(frame)) return
                if (frame[0].toInt() == Protocol.KIND_LEFT) {
                    val who = roster.firstOrNull { it.linkId == frame[1].toInt() }?.name ?: "Player ${frame[1] + 1}"
                    listener.onPartnerLeft(who)
                }
                listener.onLinkFrame(frame)
            }
            Protocol.CONTROL -> when (val c = Protocol.decodeControl(msg)) {
                is Control.Welcome -> {
                    roomName = c.room.ifEmpty { invite.roomName }
                    roster = c.players
                    joined = true
                    listener.onJoined(roomName, c.linkId, roster)
                }
                is Control.Roster -> if (joined) {
                    roster = c.players
                    listener.onJoined(roomName, c.linkId, roster)
                }
                is Control.Error -> end(p, c.message)
                is Control.Closed -> end(p, c.reason)
                else -> {}
            }
        }
    }

    private fun end(p: Pipe, message: String) {
        if (!finish()) return
        p.close("bye")
        if (joined) listener.onSessionLost(message) else listener.onFatal(message)
    }

    /** True the first time only: exactly one of onFatal / onSessionLost per session. */
    private fun finish(): Boolean = synchronized(lock) {
        if (done) false else true.also { done = true }
    }

    companion object {
        // connIds handed to each transport so far (weak keys: a closed transport's set goes with it).
        private val used = WeakHashMap<Transport, MutableSet<Long>>()

        /** False if [connId] was already given to [t]'s connect. */
        private fun claim(t: Transport, connId: Long) = synchronized(used) { used.getOrPut(t) { HashSet() }.add(connId) }
    }
}
