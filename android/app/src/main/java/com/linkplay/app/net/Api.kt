package com.linkplay.app.net

import java.net.InetSocketAddress

/*
 * Contracts shared by the link backends (phone-hosted room, guest)
 * and the UI. Everything in this package is plain JVM code (no android.*), so it
 * can be unit-tested on a desktop JVM with scripts/kt.sh.
 */

/** A player in a room as everyone sees it. */
data class RoomPlayer(val linkId: Int, val name: String, val connected: Boolean)

/** The ROM's identity; a room only takes players whose ROM has the same SHA-1. */
data class RomId(val sha1: String, val title: String, val code: String, val size: Long)

/** Callbacks from a link backend. Called on a background thread. */
interface LinkListener {
    fun onStatus(text: String)
    fun onJoined(roomName: String, linkId: Int, players: List<RoomPlayer>)
    /** u8 kind (1 handshake, 2 entry, 3 player left), u8 player, payload (2 / 64 / 0 bytes). */
    fun onLinkFrame(frame: ByteArray)
    fun onPartnerLeft(name: String)
    /** The link is gone for good: the local GBA should see a pulled cable. */
    fun onSessionLost(reason: String)
    /** Could not join at all (wrong ROM, room full, bad code...). */
    fun onFatal(message: String)
}

/** Where the local GBA's link traffic goes. */
interface LinkBackend {
    /** From Native.netPoll: u8 kind (1 handshake, 2 command), u8 0, u16le words[1 | 8]. */
    fun sendLink(payload: ByteArray)
    fun leave()
    fun close()
}

/** One reliable, ordered, encrypted message stream between the host and one guest. */
interface Pipe {
    /** Queues one message (at most MAX_MESSAGE bytes). Thread-safe, never blocks. */
    fun send(message: ByteArray)
    /** Ends the stream; the other side gets onClosed soon after (best effort). */
    fun close(reason: String)
    /** Human-readable path, e.g. "[2401:4900::1]:50123 IPv6 direct". */
    val path: String

    companion object {
        const val MAX_MESSAGE = 1024
    }
}

/** Receives a pipe's events, in order, on the transport's thread. Must not block. */
interface PipeHandler {
    fun onMessage(pipe: Pipe, message: ByteArray)
    fun onClosed(pipe: Pipe, reason: String)
}

/**
 * Peer-to-peer transport for one room (implemented by UdpTransport): one UDP
 * socket, every packet encrypted and authenticated with the room key, NAT /
 * firewall hole punching, and a reliable ordered Pipe per guest.
 */
interface Transport {
    /** Local UDP port (the same for IPv4 and IPv6); goes into codes with each local address. */
    val port: Int

    /**
     * Host: a guest reached us (or answered our probes). Return the handler for its
     * pipe, or null to refuse it. Called on the transport thread.
     */
    fun setAcceptor(acceptor: (connId: Long, pipe: Pipe) -> PipeHandler?)

    /** Host: send probes to a guest's candidates (from its reply code) so both firewalls open. */
    fun probe(connId: Long, candidates: List<Candidate>)

    /**
     * Guest: keep trying every host candidate until one answers. [onOpen] runs once
     * with the pipe; afterwards [handler] gets its messages. If no path works within
     * the connect timeout, handler.onClosed is called with a reason.
     */
    fun connect(connId: Long, hostCandidates: List<Candidate>, handler: PipeHandler, onOpen: (Pipe) -> Unit)

    /** Optional helper: asks a STUN server what our public address looks like (blocking, null on failure). */
    fun stun(server: InetSocketAddress, timeoutMs: Int = 1500): InetSocketAddress?

    fun close()
}

/** A way to reach a peer, as written into invite / reply codes. */
data class Candidate(val address: InetSocketAddress, val kind: Kind) {
    enum class Kind(val code: Int) {
        LAN(1),    // private IPv4 on the local network
        IPV6(2),   // global IPv6 address of this device
        MAPPED(3), // public IPv4:port learned from a router port mapping (UPnP / NAT-PMP / PCP) or STUN
        ;
        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
        }
    }
}

/** Secret material from an invite: identifies the room and keys all its traffic. */
class RoomKey(val roomId: Long, val secret: ByteArray) {
    init {
        require(secret.size == 16) { "room secret must be 16 bytes" }
    }
}

/** GBA timing used by the sequencer: 280896 cycles at 16.78 MHz. */
const val FRAME_NS = 16_742_706L
