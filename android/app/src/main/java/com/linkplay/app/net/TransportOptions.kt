package com.linkplay.app.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Knobs for [UdpTransport]. The defaults are the protocol's; tests shrink the timeouts and use
 * the filters to simulate loss and firewalls.
 */
data class TransportOptions(
    /** Local UDP port, 0 = any free one. */
    val port: Int = 0,
    /**
     * null = one dual-stack socket on the IPv6 wildcard (IPv4-only if the platform has no IPv6).
     * Tests bind a loopback address instead, which also keeps macOS's firewall from asking.
     */
    val bindAddress: InetAddress? = null,
    /** Runs once on the bound socket before any traffic, e.g. Android's Network.bindSocket. */
    val socketHook: (DatagramSocket) -> Unit = {},
    /** No authenticated packet from the peer for this long: the pipe is dead ("timeout"). */
    val deadTimeoutMs: Long = 20_000,
    /**
     * A guest that has not reached the host by then gives up. Long enough for a slow chat round trip:
     * the guest's reply code goes to the host over WhatsApp and the host pastes it minutes later.
     */
    val connectTimeoutMs: Long = 15 * 60_000,
    /** Guest: HELLO to every host candidate this often during the first [fastPhaseMs], then every [slowIntervalMs]. */
    val helloIntervalMs: Long = 200,
    /** Host: PROBE to every candidate of a reply code this often during the first [fastPhaseMs], then every [slowIntervalMs]. */
    val probeIntervalMs: Long = 200,
    /** How long HELLOs and PROBEs go out at their fast rate; a guest that hears a PROBE speeds up again for this long. */
    val fastPhaseMs: Long = 60_000,
    /** HELLO / PROBE interval after the fast phase: still keeps firewall and NAT state open, at a fifth of the traffic. */
    val slowIntervalMs: Long = 1000,
    /** Host: how long the PROBEs for one reply code go on (unless that guest's HELLO arrives first). */
    val probeDurationMs: Long = 15 * 60_000,
    /** After the first WELCOME, how long the guest waits for a better path (LAN > IPv6 > mapped). */
    val pathSettleMs: Long = 300,
    /**
     * New messages are held this long so several share one packet. 0 = send at once: link traffic is
     * a chain of small messages and every hop adds to a battle's round trips (messages that pile up
     * while the worker is busy still share a packet).
     */
    val flushDelayMs: Long = 0,
    /** Received data with nothing to send back: an ack-only packet goes out after this. */
    val ackDelayMs: Long = 10,
    /** Idle pipes send an (ack-only) packet this often, which keeps NAT / firewall state alive. */
    val keepaliveMs: Long = 1000,
    /** Pipe.close waits at most this long for its last messages to be acked before the BYE. */
    val closeLingerMs: Long = 2000,
    /** More unacked messages than this and the pipe closes ("too far behind"). */
    val maxBacklog: Int = 4000,
    /**
     * Strangers (anything but the addresses we talk to) whose datagrams fail authentication or name a
     * connection we don't have: each source (an IPv4 address, or an IPv6 /64) may cost us that many
     * decryptions per second after a burst of [strangerBurst]; beyond that its datagrams are dropped unread.
     */
    val strangerPerSecond: Double = 50.0,
    val strangerBurst: Int = 100,
    /**
     * All strangers together may cost that many decryptions per second after a burst of [strangerTotalBurst],
     * however many sources they use. Sources on an open pipe's own IPv4 address or /64 (e.g. that peer after
     * its NAT moved it to another port) have a second allowance of the same size, so a flood from elsewhere
     * can't keep the peer from its pipe. While a flood drains this, a new guest can't get in.
     */
    val strangerTotalPerSecond: Double = 500.0,
    val strangerTotalBurst: Int = 1000,
    /**
     * How many sources are remembered. One is forgotten (least recently heard from first) only once it has
     * all its tokens back; while the table is full of others, a source it doesn't hold is not let in.
     */
    val maxStrangerSources: Int = 1024,
    /**
     * Host: a guest's HELLO opened a pipe for [connId] via [path]; [afterProbe] says we were probing its
     * reply code's addresses at the time (otherwise the invite alone got it here). Transport thread; must not block.
     */
    val onGuestArrived: (connId: Long, path: String, afterProbe: Boolean) -> Unit = { _, _, _ -> },
    /**
     * Guest: the host answered [afterMs] after connect() and the pipe opened via [path]; [probed] says the
     * host's PROBEs reached us first (it had our reply code). Transport thread; must not block.
     */
    val onConnected: (connId: Long, path: String, afterMs: Long, probed: Boolean) -> Unit = { _, _, _, _ -> },
    /** Tests: return false to drop a datagram we are about to send. */
    val outboundFilter: ((ByteArray, InetSocketAddress) -> Boolean)? = null,
    /** Tests: return false to drop a datagram we just received (before anything looks at it). */
    val inboundFilter: ((ByteArray, InetSocketAddress) -> Boolean)? = null,
    val log: (String) -> Unit = {},
)
