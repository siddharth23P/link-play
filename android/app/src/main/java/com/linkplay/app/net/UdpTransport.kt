package com.linkplay.app.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Phone-to-phone [Transport]: one UDP socket, every packet sealed by [PacketCrypto], hole punching by
 * HELLO / WELCOME / PROBE, and a reliable ordered [Pipe] ([ReliableStream]) per guest.
 *
 * Plaintext of every packet: u8 type (bit 7 set when the host sent it), u64 connId (the guest's random
 * id), u64 counter (per sender per connId, strictly increasing), body:
 *   HELLO   u8 protoVersion   guest -> every host candidate until open, and to every PROBE's source
 *   WELCOME (empty)           host -> the source of each HELLO
 *   PROBE   (empty)           host -> each guest candidate after probe(), until that guest's HELLO arrives
 *   DATA    u32 ack, u8 count, count x [u32 seq][u16 len][bytes]
 *   BYE     u8 len, utf-8 reason
 * Both directions share the room key, so the sender's side bit is what stops one of our own packets,
 * sent back to us, from passing for the peer's (its counter, acks and messages); such packets are refused.
 *
 * Waiting: a reply code can take minutes to come back over a chat app, so a guest keeps trying for 15 min
 * and the host probes a reply code's addresses for 15 min: every 200 ms for the first minute, then every
 * second (still enough to keep firewall and NAT state open, and a fifth of the traffic). A guest that hears
 * a PROBE goes back to 200 ms for a minute: the host has its reply code now.
 *
 * Strangers: the host's socket is reachable from the whole internet (IPv6 on mobile data mostly is), so
 * whatever anyone sends must be cheap to throw away. A datagram that isn't shaped like ours (size, magic,
 * version) is dropped before any crypto. One that fails authentication, names a connection we don't
 * have, or replays one of a connection's packets from elsewhere, costs its source (an IPv4 address or an
 * IPv6 /64) a token: after a burst of 100, 50 per second; and all strangers together may cost at most
 * 500 decryptions a second after a burst of 1000, however many sources a flood uses ([SourceLimiter]).
 * A source without tokens is dropped unread. No state is made for anyone before an authentic HELLO, and
 * the table of sources is bounded without ever handing a forgotten source a fresh burst.
 *
 * Who is exempt: only the exact addresses we talk to (open pipes, a guest's host candidates, the targets
 * of our PROBEs), so a flood with a forged source can't cut a live pipe. A peer that turns up at a new
 * address (NAT rebinding, network switch) is a stranger until its first fresh DATA moves the path. On the
 * peer's own IPv4 address or /64 it is counted per port, from an allowance of its own, so junk from its
 * IP (someone behind the same carrier NAT) doesn't stop it; from anywhere else it shares the strangers'
 * total, so while a flood from many sources lasts, a peer that switched networks may lose its pipe, and a
 * new guest may not get in until it stops. Limits by IP can't tell a guest from a flooder: the real fix is
 * a cheap keyed check before any decryption, which needs a new packet format (both phones updated).
 *
 * Replays: each pipe keeps the highest counter seen and which of the 64 below it arrived. A repeated
 * counter (or one older than that window) is a replay: it is never answered (HELLO -> WELCOME,
 * PROBE -> HELLO), never counts as a sign of life and never chooses a path. A late packet that was never
 * seen still counts once, so a handshake packet overtaken on a faster path is answered all the same.
 *
 * Paths: the guest takes the source of the first WELCOME, or a better kind (LAN > IPv6 > mapped) if one
 * answers within 300 ms. Afterwards a DATA with a counter above every one seen so far from a new address
 * moves the path (NAT rebinding, network switch); late or replayed packets never do. Only DATA moves paths
 * because late handshake packets are fresh too and would drag a settled LAN path to whichever address
 * answered last.
 *
 * Threads: a receive thread opens packets and hands them to the worker thread (at most MAX_PENDING at a
 * time, so a flood can neither grow the heap nor bury real packets), which owns all state and timers,
 * sends everything, and runs every acceptor / onOpen / handler callback.
 *
 * Closing is reported to the other side only: pipe.close and transport.close never call the local
 * handler's onClosed. onClosed means something happened TO this pipe: the peer's BYE (with its reason),
 * "timeout", "too far behind", a guest that could not reach the host, or a connect that cannot start
 * (its connId is already running or was used before, or the transport is closed).
 */
class UdpTransport(
    key: RoomKey,
    private val isHost: Boolean,
    private val options: TransportOptions = TransportOptions(),
) : Transport {
    private val crypto = PacketCrypto(key)
    private val log = options.log
    private val socket = openSocket()
    override val port: Int = socket.localPort

    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val closed = AtomicBoolean()
    private val lifecycle = Any() // orders connect() against close(): a connect either runs or hears why not
    @Volatile private var stopping = false
    private val side = if (isHost) FROM_HOST else 0
    private val pending = AtomicInteger() // packets the receive thread handed over that the worker has not taken yet
    private var overflows = 0L // receive thread
    private val worker = Thread(::workLoop, "linkplay-udp")
    private val receiver = Thread(::receiveLoop, "linkplay-udp-rx")
    private val random = SecureRandom()

    // Worker-thread state.
    private val conns = HashMap<Long, Conn>()
    private val probes = HashMap<Long, Probe>()
    private val tombs = object : LinkedHashMap<Long, Tomb>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Tomb>) = size > MAX_TOMBS
    }
    private var acceptor: ((Long, Pipe) -> PipeHandler?)? = null
    private val scratch = ByteBuffer.allocate(MAX_PLAIN)
    private var sendErrors = 0
    private var sendErrorsLogged = 0 // sendErrors when one was last logged
    private var sendErrorLogAt: Long? = null // System.nanoTime() of that log line
    private var knownDirty = false // [known] is out of date (worker)

    /** The addresses we talk to: exempt from [limiter]. Rebuilt by the worker, read by the receive thread. */
    @Volatile private var known: Set<InetSocketAddress> = emptySet()
    /** The connIds we have a pipe or probes for; a stranger's packet for any other costs it a token. Like [known]. */
    @Volatile private var knownIds: Set<Long> = emptySet()
    /** [SourceLimiter.key] of each open pipe's peer: strangers there are counted per port. Like [known]. */
    @Volatile private var peerKeys = LongArray(0)
    private val limiter = SourceLimiter(options.strangerPerSecond, options.strangerBurst, options.maxStrangerSources,
        options.strangerTotalPerSecond, options.strangerTotalBurst)
    private val limitLoggedAt = AtomicLong(Long.MIN_VALUE) // when we last said a source or the strangers ran out

    private class StunWait(val server: InetSocketAddress, val answer: CompletableFuture<InetSocketAddress>)
    private val stunWaits = ConcurrentHashMap<String, StunWait>()

    /**
     * Datagrams that were neither ours nor STUN, did not authenticate (wrong key, corrupt, truncated),
     * came back to the side that sent them, came from a source over its allowance ([limited]), or broke
     * something while being read.
     */
    internal val rejected = AtomicLong()
    /** Of [rejected]: dropped unread because their source used up its allowance. */
    internal val limited = AtomicLong()
    /** Of [rejected]: decrypted, and not authentic. Each one was some CPU spent on a stranger. */
    internal val authFailures = AtomicLong()

    private val helloNs = options.helloIntervalMs * MS
    private val probeNs = options.probeIntervalMs * MS
    private val fastNs = options.fastPhaseMs * MS
    private val slowNs = options.slowIntervalMs * MS
    private val settleNs = options.pathSettleMs * MS
    private val flushNs = options.flushDelayMs * MS
    private val ackNs = options.ackDelayMs * MS
    private val keepaliveNs = options.keepaliveMs * MS
    private val deadNs = options.deadTimeoutMs * MS

    init {
        worker.isDaemon = true
        receiver.isDaemon = true
        worker.start()
        receiver.start()
    }

    // ------------------------------------------------------------------ Transport

    /** After close() this does nothing. */
    override fun setAcceptor(acceptor: (connId: Long, pipe: Pipe) -> PipeHandler?) {
        check(isHost) { "only the host accepts guests" }
        if (closed.get()) return
        post { this.acceptor = acceptor }
    }

    /** After close() this does nothing. */
    override fun probe(connId: Long, candidates: List<Candidate>) {
        check(isHost) { "only the host probes" }
        if (closed.get()) return
        post {
            if (conns.containsKey(connId) || tombs.containsKey(connId)) {
                log("probing ${hex(connId)}: not needed, it ${if (conns.containsKey(connId)) "is connected already" else "has finished"}")
                return@post
            }
            val now = System.nanoTime()
            val p = probes.getOrPut(connId) { Probe(connId) }
            for (c in candidates) if (!c.address.isUnresolved && p.targets.size < MAX_TARGETS) {
                p.targets.add(c.address)
                p.kinds[c.address] = c.kind
            }
            p.until = now + options.probeDurationMs * MS
            p.fastUntil = now + fastNs
            p.nextAt = now
            knownDirty = true
            log("probing ${hex(connId)} -> ${p.targets.joinToString { describe(it, p.kinds[it] ?: inferKind(it.address)) }}")
        }
    }

    /**
     * Every call ends in onOpen or handler.onClosed. Each attempt needs its own connId (and so its own
     * reply code): the host keeps a record of a finished one too and would answer a second try with BYE.
     * A connId that is still connecting or was used before is refused through onClosed; after close(),
     * onClosed runs at once on the calling thread, as the transport's thread is gone.
     */
    override fun connect(connId: Long, hostCandidates: List<Candidate>, handler: PipeHandler, onOpen: (Pipe) -> Unit) {
        check(!isHost) { "only a guest connects" }
        synchronized(lifecycle) {
            // Queued before close()'s own task, so it runs before the worker stops (and close() then ends it).
            if (!closed.get()) return post { startConnect(connId, hostCandidates, handler, onOpen) }
        }
        refuse(connId, handler, "the link was closed")
    }

    private fun startConnect(connId: Long, hostCandidates: List<Candidate>, handler: PipeHandler, onOpen: (Pipe) -> Unit) {
        if (conns.containsKey(connId)) return refuse(connId, handler, "this connection attempt is already running")
        if (tombs.containsKey(connId)) return refuse(connId, handler, "this connection attempt was already used; start a new one")
        val c = Conn(connId, outgoing = true)
        c.handler = handler
        c.onOpen = onOpen
        for (h in hostCandidates) if (!h.address.isUnresolved && c.targets.size < MAX_TARGETS) {
            c.targets.add(h.address)
            c.kinds.putIfAbsent(h.address, h.kind)
        }
        val now = System.nanoTime()
        c.startedAt = now
        c.giveUpAt = now + options.connectTimeoutMs * MS
        c.fastUntil = now + fastNs
        c.nextHelloAt = now
        conns[connId] = c
        knownDirty = true
        log("connecting ${hex(connId)} to ${c.targets.joinToString { format(it) }}")
    }

    /** A connect that cannot start ends like a failed attempt, with a pipe that never opens. */
    private fun refuse(connId: Long, handler: PipeHandler, reason: String) {
        val never = Conn(connId, outgoing = true).apply { state = CLOSED; closing = true; path = "not connected" }
        log("${hex(connId)} not started: $reason")
        safely("onClosed") { handler.onClosed(never, reason) }
    }

    /** Blocks the calling thread (not the transport's) until the answer or the timeout. */
    override fun stun(server: InetSocketAddress, timeoutMs: Int): InetSocketAddress? {
        if (closed.get() || server.isUnresolved) return null
        val txId = ByteArray(12).also { random.nextBytes(it) }
        val id = String(txId, Charsets.ISO_8859_1)
        val wait = StunWait(server, CompletableFuture())
        stunWaits[id] = wait
        try {
            val request = Stun.bindingRequest(txId)
            val deadline = System.nanoTime() + timeoutMs * MS
            var interval = 250L
            while (true) {
                post { sendRaw(request, server) } // the worker sends: Android forbids network I/O on the UI thread
                val left = (deadline - System.nanoTime()) / MS
                if (left <= 0) return null
                try {
                    return wait.answer.get(minOf(interval, left), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    interval = minOf(interval * 2, 1000)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            return null
        } finally {
            stunWaits.remove(id)
        }
    }

    /** Says goodbye to every peer (best effort, nothing is retried) and stops; no local onClosed. */
    override fun close() {
        synchronized(lifecycle) {
            if (!closed.compareAndSet(false, true)) return
            post {
                val now = System.nanoTime()
                for (c in conns.values.toList()) {
                    takeOutbox(c, now)
                    if (c.state == OPEN || c.state == DRAINING) sendData(c, now) // what was sent just before close()
                    val reason = if (c.state == DRAINING) c.byeReason else if (isHost) "the host closed the room" else "the guest left"
                    finish(c, reason, notify = false, bye = true)
                }
                probes.clear()
                stopping = true
            }
        }
        if (Thread.currentThread() === worker) return // called from a callback: the task above runs next
        worker.join(1000)
        stopping = true
        socket.close()
        receiver.join(1000)
    }

    // ------------------------------------------------------------------ pipes

    private inner class Conn(val connId: Long, val outgoing: Boolean) : Pipe {
        var handler: PipeHandler? = null
        var onOpen: ((Pipe) -> Unit)? = null
        var state = CONNECTING
        var addr: InetSocketAddress? = null
        val kinds = HashMap<InetSocketAddress, Candidate.Kind>() // what the codes said about addresses
        var txCounter = 0L
        val rx = ReplayWindow()
        var lastHeard = 0L
        var lastSent = 0L
        var flushAt = NEVER
        var ackAt = NEVER
        var lingerUntil = NEVER
        var byeReason = ""
        val stream = ReliableStream()
        val outbox = ConcurrentLinkedQueue<ByteArray>()
        val outboxPosted = AtomicBoolean()
        @Volatile var closing = false

        // Guest, until open.
        val targets = LinkedHashSet<InetSocketAddress>()
        var startedAt = 0L
        var nextHelloAt = NEVER
        var fastUntil = 0L // HELLOs at the fast rate until then
        var giveUpAt = NEVER
        val welcomes = ArrayList<InetSocketAddress>()
        var settleAt = NEVER
        var probed = false // a PROBE reached us: the host has our reply code
        val probedFrom = HashSet<InetSocketAddress>() // where PROBEs came from (logged once each)

        @Volatile override var path = "connecting"

        override fun send(message: ByteArray) {
            require(message.size <= Pipe.MAX_MESSAGE) { "message of ${message.size} bytes is over ${Pipe.MAX_MESSAGE}" }
            if (closing || closed.get()) return
            outbox.add(message.copyOf())
            if (outboxPosted.compareAndSet(false, true)) post {
                outboxPosted.set(false)
                takeOutbox(this, System.nanoTime())
            }
        }

        override fun close(reason: String) {
            if (closing) return
            closing = true
            post { startClose(this, reason) }
        }
    }

    private class Probe(val connId: Long) {
        val targets = LinkedHashSet<InetSocketAddress>()
        val kinds = HashMap<InetSocketAddress, Candidate.Kind>()
        var until = 0L
        var fastUntil = 0L
        var nextAt = 0L
        var txCounter = 0L
    }

    /** A finished connId: refuse it for good, and answer its peer's fresh packets with another BYE. */
    private class Tomb(var txCounter: Long, var rxHighest: Long, val reason: String)

    private fun takeOutbox(c: Conn, now: Long) {
        if (c.state != OPEN && c.state != DRAINING) return // a guest's pipe is not handed out before open
        var any = false
        while (true) {
            c.stream.queue(c.outbox.poll() ?: break)
            any = true
        }
        if (!any) return
        if (c.stream.backlog > options.maxBacklog) {
            finish(c, "too far behind", notify = true, bye = true)
            return
        }
        if (c.flushAt == NEVER) c.flushAt = now + flushNs
    }

    private fun startClose(c: Conn, reason: String) {
        when (c.state) {
            CONNECTING -> finish(c, reason, notify = false, bye = true)
            OPEN -> {
                takeOutbox(c, System.nanoTime())
                if (c.state != OPEN) return
                // Let the last messages (e.g. an error reply) get acked before the BYE.
                c.state = DRAINING
                c.byeReason = reason
                c.lingerUntil = System.nanoTime() + options.closeLingerMs * MS
            }
        }
    }

    private fun finish(c: Conn, reason: String, notify: Boolean, bye: Boolean) {
        if (c.state == CLOSED) return
        val closedHere = c.closing // pipe.close() was called: its owner is done with it, tell nobody locally
        c.state = CLOSED
        c.closing = true
        conns.remove(c.connId)
        probes.remove(c.connId)
        knownDirty = true
        val tomb = Tomb(c.txCounter, c.rx.highest, reason)
        tombs[c.connId] = tomb
        if (bye) {
            val to = c.addr?.let { listOf(it) } ?: c.targets
            // Twice, as nothing resends a BYE; a peer that still talks to us later gets another one.
            repeat(2) { for (a in to) sendBye(c.connId, ++tomb.txCounter, reason, a) }
        }
        log("${hex(c.connId)} closed: $reason")
        if (notify && !closedHere) c.handler?.let { h -> safely("onClosed") { h.onClosed(c, reason) } }
    }

    private fun open(c: Conn, now: Long) {
        val best = c.welcomes.minByOrNull { rank(kindOf(c, it)) } ?: return
        c.state = OPEN
        c.addr = best
        c.path = describe(best, kindOf(c, best))
        c.lastHeard = now
        c.lastSent = now - keepaliveNs // a DATA right away tells the host which path we chose
        c.settleAt = NEVER
        c.giveUpAt = NEVER
        knownDirty = true
        val afterMs = (now - c.startedAt) / MS
        log("${hex(c.connId)} pipe open via ${c.path} after ${String.format(Locale.ROOT, "%.1f", afterMs / 1000.0)} s " +
            "(${if (c.probed) "after reply code: the host was probing us" else "invite only"})")
        safely("onConnected") { options.onConnected(c.connId, c.path, afterMs, c.probed) }
        c.onOpen?.let { f -> c.onOpen = null; safely("onOpen") { f(c) } }
        takeOutbox(c, now)
    }

    private fun movePath(c: Conn, to: InetSocketAddress) {
        val was = c.path
        c.addr = to
        c.path = describe(to, kindOf(c, to))
        knownDirty = true
        log("${hex(c.connId)} path moved: $was -> ${c.path}")
    }

    private fun kindOf(c: Conn, a: InetSocketAddress) = c.kinds[a] ?: inferKind(a.address)

    // ------------------------------------------------------------------ incoming

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        var errors = 0
        while (!stopping) {
            try {
                packet.setData(buf)
                socket.receive(packet)
                errors = 0
            } catch (e: IOException) {
                if (stopping || socket.isClosed) break
                if (++errors % 100 == 1) log("receive failed: ${e.message}")
                Thread.sleep(5)
                continue
            }
            val src = packet.socketAddress as? InetSocketAddress ?: continue
            try {
                onDatagram(buf, packet.length, src)
            } catch (e: Exception) { // anyone can send us anything: no datagram may stop this thread (or, on Android, the app)
                rejected.incrementAndGet()
            }
        }
    }

    /** Receive thread: answers our STUN lookups, opens our packets and hands them to the worker. */
    private fun onDatagram(buf: ByteArray, len: Int, src: InetSocketAddress) {
        val filter = options.inboundFilter
        if (filter != null && !filter(buf.copyOf(len), src)) return
        if (Stun.isStun(buf, len)) {
            if (stunWaits.isNotEmpty()) onStun(buf, len, src) // nobody asked: not even parsed
            return
        }
        // Not even shaped like ours: thrown away before any crypto, and its source isn't remembered.
        if (len < MIN_PACKET || len > MAX_PACKET || buf[0] != PacketCrypto.MAGIC || buf[1] != PacketCrypto.VERSION) {
            rejected.incrementAndGet()
            return
        }
        val now = System.nanoTime()
        val stranger = src !in known
        var key = 0L
        var near = false
        if (stranger) {
            val ipKey = SourceLimiter.key(src.address)
            near = ipKey in peerKeys
            key = if (near) SourceLimiter.portKey(ipKey, src) else ipKey
            if (!limiter.allows(key, near, now)) {
                limited.incrementAndGet()
                rejected.incrementAndGet()
                return
            }
        }
        val plain = crypto.open(buf, len)
        if (plain == null) {
            authFailures.incrementAndGet()
            rejected.incrementAndGet()
            if (stranger) useless(src, key, near, now)
            return
        }
        val body = ByteBuffer.wrap(plain) // at least PLAIN_HEADER bytes: len >= MIN_PACKET
        val type = body.get().toInt() and 0xff
        if (((type and FROM_HOST) != 0) == isHost) { // our own packet, sent back to us
            rejected.incrementAndGet()
            if (stranger) useless(src, key, near, now)
            return
        }
        val connId = body.long
        val counter = body.long
        // Authentic, but for a connection we don't have: a new guest's HELLO (one token per attempt, of 100),
        // a late packet of a finished one, or someone with the invite making trouble. Charged here rather than
        // by the worker, so a flood of them is cut off as promptly as garbage is.
        if (stranger && connId !in knownIds) useless(src, key, near, now)
        // Dropped like on the wire (the stream resends) rather than queued without limit behind a flood.
        if (pending.get() >= MAX_PENDING) {
            if (overflows++ % 1000 == 0L) log("worker is behind: dropping packets")
            return
        }
        pending.incrementAndGet()
        post {
            pending.decrementAndGet()
            onPacket(src, type and FROM_HOST.inv(), connId, counter, body)
        }
    }

    /** A stranger's datagram cost us a decryption for nothing: charge its source, and say when one runs dry. */
    private fun useless(src: InetSocketAddress, key: Long, near: Boolean, now: Long) {
        val ran = limiter.charge(key, near, now)
        if (ran == 0) return
        val last = limitLoggedAt.get()
        if (last != Long.MIN_VALUE && now - last < LIMIT_LOG_NS) return
        if (!limitLoggedAt.compareAndSet(last, now)) return
        val who = when {
            ran and SourceLimiter.TOTAL == 0 -> format(src) + if (near) "" else if (src.address is Inet6Address) " (its /64)" else " (its IP)"
            near -> "addresses next to a peer's (last ${format(src)})"
            else -> "strangers all together (last ${format(src)})"
        }
        log("$who sent too much that isn't ours: their packets are dropped unread for now " +
            "(so far ${limited.get()} dropped unread, ${authFailures.get()} failed authentication)")
    }

    /** The worker's version of [useless], for a replay from an address we don't talk to. */
    private fun useless(src: InetSocketAddress, now: Long) {
        val ipKey = SourceLimiter.key(src.address)
        val near = ipKey in peerKeys
        useless(src, if (near) SourceLimiter.portKey(ipKey, src) else ipKey, near, now)
    }

    private fun onStun(buf: ByteArray, len: Int, src: InetSocketAddress) {
        val r = Stun.parseResponse(buf, len) ?: return
        val wait = stunWaits[String(r.txId, Charsets.ISO_8859_1)] ?: return
        if (wait.server.address != src.address || wait.server.port != src.port) return
        r.mapped?.let { wait.answer.complete(it) }
    }

    private fun onPacket(src: InetSocketAddress, type: Int, connId: Long, counter: Long, body: ByteBuffer) {
        val now = System.nanoTime()
        val c = conns[connId] ?: return onStranger(src, type, connId, counter, now)
        val newest = counter > c.rx.highest
        val replay = !c.rx.firstSight(counter)
        // Someone else replaying a captured packet: as useless as a forgery, and limited the same way.
        if (replay && src !in known) useless(src, now)
        if (c.state == CONNECTING) { // only a guest's pipe is ever connecting
            when (type) {
                WELCOME, DATA -> if (!replay) welcomed(c, src, now) // DATA: the host has us, its WELCOME got lost
                PROBE -> if (!replay) {
                    if (c.probedFrom.size < MAX_TARGETS && c.probedFrom.add(src)) {
                        log("${hex(c.connId)} PROBE from ${describe(src, kindOf(c, src))}: the host has our reply code")
                    }
                    // The host's own address may not be in its invite (another IPv6 address, NAT):
                    // answering where the PROBE came from is what gets through both firewalls.
                    if (c.targets.size < MAX_TARGETS && c.targets.add(src)) knownDirty = true
                    c.probed = true
                    // The host has our reply code now and probes at its fast rate: so do we, for as long.
                    c.fastUntil = maxOf(c.fastUntil, now + fastNs)
                    c.nextHelloAt = minOf(c.nextHelloAt, now + helloNs)
                    sendHello(c, src)
                }
                BYE -> finish(c, readReason(body), notify = true, bye = false)
            }
            return
        }
        if (!replay) c.lastHeard = now
        when (type) {
            HELLO -> if (!c.outgoing && !replay) sendWelcome(c, src) // a replayed HELLO costs us nothing
            DATA -> {
                if (newest && src != c.addr) movePath(c, src)
                onData(c, body, now) // replays too: the stream drops what it already has
            }
            BYE -> finish(c, readReason(body), notify = true, bye = false)
            // WELCOME / PROBE after open: stragglers from the handshake.
        }
    }

    /**
     * A packet for a connId without a pipe: a new guest, or someone we already said goodbye to. Only an
     * authentic HELLO to the host makes state (the receive thread already charged its source a token).
     */
    private fun onStranger(src: InetSocketAddress, type: Int, connId: Long, counter: Long, now: Long) {
        val tomb = tombs[connId]
        if (tomb != null) {
            if (type != BYE && counter > tomb.rxHighest) {
                tomb.rxHighest = counter
                sendBye(connId, ++tomb.txCounter, tomb.reason, src)
            }
            return
        }
        if (!isHost || type != HELLO) return
        val accept = acceptor ?: return // not ready yet: the guest keeps trying
        val c = Conn(connId, outgoing = false)
        c.state = OPEN
        c.addr = src
        c.rx.firstSight(counter)
        c.lastHeard = now
        c.lastSent = now
        val probe = probes.remove(connId)
        if (probe != null) {
            c.txCounter = probe.txCounter // counters keep rising across PROBE -> WELCOME
            c.kinds.putAll(probe.kinds)
        }
        val probed = probe != null
        c.path = describe(src, kindOf(c, src))
        conns[connId] = c
        knownDirty = true
        val handler = safely("acceptor") { accept(connId, c) }
        if (handler == null) {
            finish(c, "the host refused the connection", notify = false, bye = true)
            return
        }
        c.handler = handler
        sendWelcome(c, src)
        // "after reply code": we were probing this guest's addresses (its reply code was pasted) when it got in.
        // Not "joined" yet: the room still checks its ROM, version and a free slot.
        log("${hex(connId)} pipe open via ${c.path} (${if (probed) "after reply code" else "invite only"})")
        safely("onGuestArrived") { options.onGuestArrived(connId, c.path, probed) }
    }

    private fun welcomed(c: Conn, src: InetSocketAddress, now: Long) {
        if (src !in c.welcomes) c.welcomes.add(src)
        if (c.settleAt == NEVER) c.settleAt = now + settleNs
        // No better kind can still answer (e.g. LAN, or an IPv6-only invite): no point waiting.
        val got = rank(kindOf(c, src))
        if (c.targets.all { rank(kindOf(c, it)) >= got }) open(c, now)
    }

    private fun onData(c: Conn, b: ByteBuffer, now: Long) {
        if (b.remaining() < 5) return
        val ack = b.int
        val count = b.get().toInt() and 0xff
        c.stream.onAck(ack, now)
        if (count == 0) return
        for (i in 0 until count) {
            if (b.remaining() < 6) break
            val seq = b.int
            val len = b.short.toInt() and 0xffff
            if (len > b.remaining()) break
            val msg = ByteArray(len).also { b.get(it) }
            c.stream.receive(seq, msg) { m ->
                val h = c.handler
                if (h != null && c.state == OPEN && !c.closing) safely("onMessage") { h.onMessage(c, m) }
            }
        }
        if (c.ackAt == NEVER) c.ackAt = now + ackNs
    }

    private fun readReason(b: ByteBuffer): String {
        if (!b.hasRemaining()) return "closed"
        val n = minOf(b.get().toInt() and 0xff, b.remaining())
        return String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ worker

    private fun post(task: () -> Unit) {
        tasks.add(task)
        LockSupport.unpark(worker)
    }

    private fun workLoop() {
        try {
            while (true) {
                while (true) {
                    val task = tasks.poll() ?: break
                    safely("task") { task() }
                }
                if (stopping) break
                val now = System.nanoTime()
                val next = safely("timers") { service(now) } ?: (now + MS)
                if (knownDirty) refreshKnown()
                val wait = next - System.nanoTime()
                if (wait > 0 && tasks.isEmpty() && !stopping) LockSupport.parkNanos(this, wait)
            }
        } finally {
            stopping = true
            socket.close()
            if (limited.get() > 0 || authFailures.get() > 0) {
                log("strangers: ${limited.get()} datagrams dropped unread, ${authFailures.get()} failed authentication")
            }
        }
    }

    /** The addresses we talk to, for the receive thread: open pipes, a guest's host candidates, PROBE targets. */
    private fun refreshKnown() {
        knownDirty = false
        val s = HashSet<InetSocketAddress>()
        for (c in conns.values) {
            c.addr?.let { s.add(it) }
            if (c.state == CONNECTING) s.addAll(c.targets)
        }
        for (p in probes.values) s.addAll(p.targets)
        known = s
        knownIds = HashSet<Long>(conns.keys).apply { addAll(probes.keys) }
        peerKeys = conns.values.mapNotNull { c -> c.addr?.let { SourceLimiter.key(it.address) } }.distinct().toLongArray()
    }

    /** Tests: how big the per-peer tables are (read on the worker, so it waits for it). */
    internal fun tableSizes(): Map<String, Int> {
        val answer = CompletableFuture<Map<String, Int>>()
        post {
            answer.complete(mapOf("conns" to conns.size, "probes" to probes.size, "tombs" to tombs.size,
                "sources" to limiter.size(), "pending" to pending.get(), "known" to known.size))
        }
        return answer.get(2, TimeUnit.SECONDS)
    }

    /** Runs due timers; returns when the next one is due. */
    private fun service(now: Long): Long {
        var next = now + 1000 * MS
        if (probes.isNotEmpty()) {
            val it = probes.values.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (now >= p.until) {
                    it.remove()
                    knownDirty = true
                    log("probing ${hex(p.connId)}: gave up, its HELLO never came")
                    continue
                }
                if (now >= p.nextAt) {
                    for (t in p.targets) sendSimple(PROBE, p.connId, ++p.txCounter, t)
                    p.nextAt = now + if (now < p.fastUntil) probeNs else slowNs
                }
                next = minOf(next, p.nextAt, p.until)
            }
        }
        if (conns.isNotEmpty()) for (c in conns.values.toTypedArray()) next = minOf(next, serviceConn(c, now))
        return next
    }

    private fun serviceConn(c: Conn, now: Long): Long {
        if (c.state == CONNECTING) {
            if (now >= c.giveUpAt) {
                finish(c, "could not reach the host", notify = true, bye = true)
                return NEVER
            }
            if (now >= c.settleAt) open(c, now) else {
                if (now >= c.nextHelloAt) {
                    for (t in c.targets) sendHello(c, t)
                    c.nextHelloAt = now + if (now < c.fastUntil) helloNs else slowNs
                }
                return minOf(c.giveUpAt, c.settleAt, c.nextHelloAt)
            }
        }
        if (c.state == CLOSED) return NEVER
        if (now - c.lastHeard >= deadNs) {
            finish(c, "timeout", notify = true, bye = true)
            return NEVER
        }
        if (c.state == DRAINING && (c.stream.allAcked || now >= c.lingerUntil)) {
            finish(c, c.byeReason, notify = false, bye = true)
            return NEVER
        }
        if (now >= c.flushAt || now >= c.ackAt || now >= c.lastSent + keepaliveNs || now >= c.stream.retransmitAt()) {
            sendData(c, now)
        }
        return minOf(minOf(c.flushAt, c.ackAt, c.lastSent + keepaliveNs), minOf(c.stream.retransmitAt(), c.lastHeard + deadNs, c.lingerUntil))
    }

    /** Sends what is due (new messages, expired ones) in as few packets as fit, or one ack-only DATA. */
    private fun sendData(c: Conn, now: Long) {
        val to = c.addr ?: return
        c.stream.startRound()
        var packets = 0
        while (true) {
            val b = begin(DATA, c.connId, c.txCounter + 1)
            val n = c.stream.fill(b, now, MAX_PLAIN - PLAIN_HEADER)
            if (n == 0 && packets > 0) break
            c.txCounter++
            transmit(to)
            packets++
            if (n == 0 || packets >= MAX_BURST || !c.stream.hasDue(now)) break
        }
        c.lastSent = now
        c.ackAt = NEVER
        c.flushAt = if (c.stream.hasUnsent()) now else NEVER // burst cut short: carry on after the inbox
    }

    private fun sendHello(c: Conn, to: InetSocketAddress) {
        begin(HELLO, c.connId, ++c.txCounter).put(PROTO_VERSION.toByte())
        transmit(to)
    }

    private fun sendWelcome(c: Conn, to: InetSocketAddress) = sendSimple(WELCOME, c.connId, ++c.txCounter, to)

    private fun sendSimple(type: Int, connId: Long, counter: Long, to: InetSocketAddress) {
        begin(type, connId, counter)
        transmit(to)
    }

    private fun sendBye(connId: Long, counter: Long, reason: String, to: InetSocketAddress) {
        val b = begin(BYE, connId, counter)
        val text = utf8Prefix(reason, 255)
        b.put(text.size.toByte()).put(text)
        transmit(to)
    }

    private fun begin(type: Int, connId: Long, counter: Long): ByteBuffer {
        scratch.clear()
        return scratch.put((type or side).toByte()).putLong(connId).putLong(counter)
    }

    private fun transmit(to: InetSocketAddress) = sendRaw(crypto.seal(scratch.array(), scratch.position()), to)

    private fun sendRaw(data: ByteArray, to: InetSocketAddress) {
        val filter = options.outboundFilter
        if (filter != null && !filter(data, to)) return
        try {
            socket.send(DatagramPacket(data, data.size, to))
        } catch (e: Exception) { // e.g. no IPv6 route, an IPv6 peer on an IPv4-only socket, or EPERM: Android cut a background app off
            sendErrors++
            // The first one right away (so a log shows when sending stopped working), then at most one
            // line per SEND_ERROR_LOG_NS with how many failed meanwhile.
            val now = System.nanoTime()
            val last = sendErrorLogAt
            if (!stopping && (last == null || now - last >= SEND_ERROR_LOG_NS)) {
                val more = sendErrors - sendErrorsLogged - 1
                log("send to ${format(to)} failed: ${e.message}" + if (more > 0) " (and $more more since the last report)" else "")
                sendErrorLogAt = now
                sendErrorsLogged = sendErrors
            }
        }
    }

    private inline fun <T> safely(what: String, f: () -> T): T? = try {
        f()
    } catch (e: Exception) {
        log("$what failed: $e")
        null
    }

    // ------------------------------------------------------------------ socket

    private fun openSocket(): DatagramSocket {
        val at = options.bindAddress
        val s = if (at != null) bound(InetSocketAddress(at, options.port)) else try {
            bound(InetSocketAddress(InetAddress.getByName("::"), options.port)) // dual-stack: v4 arrives v4-mapped
        } catch (e: Exception) {
            log("no IPv6 socket (${e.message}); IPv4 only")
            bound(InetSocketAddress(InetAddress.getByName("0.0.0.0"), options.port))
        }
        try {
            s.receiveBufferSize = 1 shl 20 // a hint; bursts of retransmissions must not overflow it
            s.sendBufferSize = 1 shl 20
        } catch (e: Exception) {
        }
        try {
            options.socketHook(s)
        } catch (e: Throwable) {
            s.close()
            throw e
        }
        return s
    }

    private fun bound(at: InetSocketAddress): DatagramSocket {
        val s = DatagramSocket(null)
        try {
            s.bind(at)
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    companion object {
        const val PROTO_VERSION = 1
        const val HELLO = 1
        const val WELCOME = 2
        const val PROBE = 3
        const val DATA = 4
        const val BYE = 5
        /** In the type byte: the host sent this packet. */
        private const val FROM_HOST = 0x80

        /** UDP payload limit; small enough for any path MTU, with or without IPv6 extension headers. */
        const val MAX_PACKET = 1200
        internal const val MAX_PLAIN = MAX_PACKET - PacketCrypto.OVERHEAD
        /** At most one "send ... failed" log line this often (the first one always goes out at once). */
        private const val SEND_ERROR_LOG_NS = 2_000_000_000L
        internal const val PLAIN_HEADER = 1 + 8 + 8
        /** The smallest packet of ours: header, nonce, tag and a plaintext header with an empty body. */
        internal const val MIN_PACKET = PacketCrypto.OVERHEAD + PLAIN_HEADER

        private const val CONNECTING = 0
        private const val OPEN = 1
        private const val DRAINING = 2
        private const val CLOSED = 3

        private const val MS = 1_000_000L
        private const val NEVER = Long.MAX_VALUE
        private const val MAX_BURST = 64
        private const val MAX_TARGETS = 16
        private const val MAX_TOMBS = 256
        /** Packets waiting for the worker: far above any real burst (64 per pipe), ~1 MB at most. */
        private const val MAX_PENDING = 1024
        /** At most one line this often about strangers running out of tokens. */
        private const val LIMIT_LOG_NS = 10_000 * MS

        /** Path preference: a LAN hop beats direct IPv6, which beats a mapped / public IPv4 port. */
        private fun rank(k: Candidate.Kind) = when (k) {
            Candidate.Kind.LAN -> 0
            Candidate.Kind.IPV6 -> 1
            Candidate.Kind.MAPPED -> 2
        }

        /** What an address we were not told about probably is. */
        fun inferKind(ip: InetAddress): Candidate.Kind = when {
            ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress -> Candidate.Kind.LAN
            ip is Inet6Address -> if (ip.address[0].toInt() and 0xfe == 0xfc) Candidate.Kind.LAN else Candidate.Kind.IPV6 // fc00::/7 is ULA
            else -> Candidate.Kind.MAPPED
        }

        /** "[2401:4900::1]:50123 IPv6 direct" */
        fun describe(a: InetSocketAddress, kind: Candidate.Kind) = "${format(a)} " + when (kind) {
            Candidate.Kind.LAN -> LAN_PATH
            Candidate.Kind.IPV6 -> "IPv6 direct"
            Candidate.Kind.MAPPED -> "public IPv4"
        }

        /** A [describe]d path that is a LAN hop (e.g. a pipe's [Pipe.path]). */
        fun isLanPath(path: String) = path.endsWith(" $LAN_PATH")

        private const val LAN_PATH = "LAN"

        /** host:port with IPv6 in brackets and compressed (RFC 5952). */
        fun format(a: InetSocketAddress): String {
            val ip = a.address ?: return "${a.hostString}:${a.port}"
            if (ip is Inet4Address) return "${ip.hostAddress}:${a.port}"
            val b = ip.address
            val groups = IntArray(8) { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }
            var bestAt = -1
            var bestLen = 1 // only runs of two or more zero groups become "::"
            var i = 0
            while (i < 8) {
                if (groups[i] != 0) { i++; continue }
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) { bestAt = i; bestLen = j - i }
                i = j
            }
            val text = if (bestAt < 0) groups.joinToString(":") { Integer.toHexString(it) } else
                groups.take(bestAt).joinToString(":") { Integer.toHexString(it) } + "::" +
                    groups.drop(bestAt + bestLen).joinToString(":") { Integer.toHexString(it) }
            return "[$text]:${a.port}"
        }

        private fun hex(connId: Long) = java.lang.Long.toHexString(connId)

        /** The longest prefix of [s] that is at most [max] UTF-8 bytes and still valid UTF-8. */
        private fun utf8Prefix(s: String, max: Int): ByteArray {
            val all = s.toByteArray(Charsets.UTF_8)
            if (all.size <= max) return all
            var n = max
            while (n > 0 && (all[n].toInt() and 0xc0) == 0x80) n-- // don't split a character
            return all.copyOf(n)
        }
    }
}

/**
 * What strangers' useless datagrams (not authentic, reflected, for a connection we don't have, or a replay
 * from elsewhere) may cost us, counted in decryptions. Two levels, both token buckets:
 *
 *  - Per source: an IPv4 address, or an IPv6 /64, which is what one phone or one home gets, so hopping
 *    between its addresses or ports buys no fresh bucket. A source starts with [burst] tokens and earns
 *    [perSecond]. A source next to a live peer (the peer's own IPv4 address or /64, another port: the peer
 *    after its NAT moved it, or someone behind the same carrier NAT) is counted per address and port
 *    instead ([portKey]), so junk from the peer's IP can't silence the peer's packets from its new port.
 *  - In total: all strangers together earn [totalPerSecond] after a burst of [totalBurst], however many
 *    sources they use (an IPv6 /48 holds 65 536 /64s). Sources next to a live peer draw from a pool of
 *    their own, so a flood from elsewhere can't keep a peer whose port changed from its pipe.
 *
 * Each useless datagram costs its source and its pool a token; while either has none, the source's
 * datagrams are dropped unread. The table holds at most [maxSources] sources. To make room it forgets the
 * least recently heard-from one, but only once that one has all its tokens back, so forgetting it loses
 * nothing: a source that spent its burst never gets a fresh one by being forgotten. While the table is
 * full of sources still paying off (a flood from more sources than it holds), a source without a bucket
 * is not let in at all. Thread-safe (the receive thread and the worker charge).
 */
internal class SourceLimiter(
    private val perSecond: Double,
    private val burst: Int,
    private val maxSources: Int,
    private val totalPerSecond: Double = perSecond * 10,
    totalBurst: Int = burst * 10,
) {
    private class Bucket(var tokens: Double, var at: Long, var started: Boolean = true)

    /** Access order: the first entry is the least recently heard-from source. */
    private val buckets = LinkedHashMap<Long, Bucket>(64, 0.75f, true)
    private val totalCap = totalBurst.toDouble()
    /** [0]: strangers in general; [1]: sources next to a live peer. Full until first used. */
    private val pools = Array(2) { Bucket(totalCap, 0L, started = false) }

    /** False while [key]'s source or its pool has no tokens: drop its datagram without reading it. */
    fun allows(key: Long, nearPeer: Boolean, now: Long): Boolean = synchronized(this) {
        val pool = pool(nearPeer, now)
        if (pool.tokens < 1.0) return false
        val b = buckets[key] ?: return hasRoom(now) // never seen (or forgotten with all its tokens back)
        refill(b, now)
        b.tokens >= 1.0
    }

    /**
     * [key]'s source sent us something useless: one token less for it and its pool. Returns what ran out
     * just now ([SOURCE] / [TOTAL] bits), for the log.
     */
    fun charge(key: Long, nearPeer: Boolean, now: Long): Int = synchronized(this) {
        var ran = if (take(pool(nearPeer, now))) TOTAL else 0
        var b = buckets[key]
        if (b == null) {
            // No room without forgetting a source that still owes us: it stays unknown, and unknown
            // sources aren't let in until the table has room again (it was let in just before it filled).
            if (!hasRoom(now)) return ran
            if (buckets.size >= maxSources) buckets.entries.iterator().run { next(); remove() }
            b = Bucket(burst.toDouble(), now)
            buckets[key] = b
        } else {
            refill(b, now)
        }
        if (take(b)) ran = ran or SOURCE
        ran
    }

    fun allows(ip: InetAddress, now: Long): Boolean = allows(key(ip), false, now)

    fun charge(ip: InetAddress, now: Long): Int = charge(key(ip), false, now)

    fun size(): Int = synchronized(this) { buckets.size }

    /** A source without a bucket can have one: the table has room, or its eldest has all its tokens back. */
    private fun hasRoom(now: Long): Boolean {
        if (buckets.size < maxSources) return true
        val eldest = buckets.values.iterator().next() // looking doesn't count as hearing from it
        refill(eldest, now)
        return eldest.tokens >= burst
    }

    private fun pool(nearPeer: Boolean, now: Long): Bucket {
        val p = pools[if (nearPeer) 1 else 0]
        if (!p.started) {
            p.started = true
            p.at = now
        }
        val dt = now - p.at
        if (dt > 0) {
            p.tokens = minOf(totalCap, p.tokens + dt * totalPerSecond / 1e9)
            p.at = now
        }
        return p
    }

    private fun refill(b: Bucket, now: Long) {
        val dt = now - b.at
        if (dt <= 0) return
        b.tokens = minOf(burst.toDouble(), b.tokens + dt * perSecond / 1e9)
        b.at = now
    }

    /** One token less; true if that was the last whole one. */
    private fun take(b: Bucket): Boolean {
        val had = b.tokens >= 1.0
        b.tokens = maxOf(0.0, b.tokens - 1.0)
        return had && b.tokens < 1.0
    }

    companion object {
        /** In what [charge] returns: the source has no tokens left. */
        const val SOURCE = 1
        /** In what [charge] returns: its pool (all strangers, or all next to a live peer) has none left. */
        const val TOTAL = 2

        /** An IPv6 address's /64; an IPv4 address under ffff:ffff::/32, a multicast prefix no packet comes from. */
        fun key(ip: InetAddress): Long {
            val b = ip.address
            if (b.size == 4) return (0xffffffffL shl 32) or prefix(b, 0, 4)
            return prefix(b, 0, 8)
        }

        /**
         * One address and port next to a live peer ([ipKey] is its [key]). A hash: a collision with
         * another key would only make two sources share a bucket.
         */
        fun portKey(ipKey: Long, a: InetSocketAddress): Long {
            val b = a.address.address
            val low = if (b.size == 16) prefix(b, 8, 8) else 0L
            var h = ipKey * -0x61c8864680b583ebL + low
            h = (h xor (h ushr 31)) * -0x40a7b892e31b1a47L + a.port
            h = (h xor (h ushr 29)) * -0x6b2fb644ecceee15L
            return h xor (h ushr 32)
        }

        private fun prefix(b: ByteArray, from: Int, n: Int): Long {
            var v = 0L
            for (i in from until from + n) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
    }
}

/**
 * Anti-replay for one peer's packet counters: the highest seen, and which of the 64 below it arrived.
 * A late packet that never arrived before still counts (once); a repeat, or anything older, does not.
 */
internal class ReplayWindow {
    var highest = 0L
        private set
    private var seen = 0L // bit i: counter highest - i arrived

    /** True the first time [counter] arrives (counters start at 1). */
    fun firstSight(counter: Long): Boolean {
        if (counter < 1) return false
        if (counter > highest) {
            val up = counter - highest
            seen = if (up >= 64) 1L else (seen shl up.toInt()) or 1L
            highest = counter
            return true
        }
        val back = highest - counter
        if (back >= 64) return false
        val bit = 1L shl back.toInt()
        if (seen and bit != 0L) return false
        seen = seen or bit
        return true
    }
}
