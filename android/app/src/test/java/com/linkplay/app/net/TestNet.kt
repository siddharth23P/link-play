package com.linkplay.app.net

import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/*
 * Test doubles for the room protocol: an in-memory network of reliable, ordered
 * pipes (with optional latency and jitter) and a fake Transport on top of it.
 * Like the real transport, every pipe callback runs on one thread.
 */

internal val TEST_ROM = RomId("0123456789abcdef0123456789abcdef01234567", "POKEMON EMER", "BPEE", 16 shl 20)

/** Runs actions at given System.nanoTime() instants, in (time, submission) order, on one thread. */
internal class MemScheduler(name: String) : AutoCloseable {
    private class Task(val due: Long, val n: Long, val action: () -> Unit) : Delayed {
        override fun getDelay(unit: TimeUnit) = unit.convert(due - System.nanoTime(), TimeUnit.NANOSECONDS)
        override fun compareTo(other: Delayed): Int {
            val o = other as Task
            return if (due != o.due) due.compareTo(o.due) else n.compareTo(o.n)
        }
    }

    private val queue = DelayQueue<Task>()
    private val counter = AtomicLong()
    @Volatile private var running = true
    val errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private val thread = Thread({
        while (running) {
            val t = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
            try {
                t.action()
            } catch (e: Throwable) {
                errors.add(e)
            }
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    fun at(due: Long, action: () -> Unit) {
        queue.add(Task(due, counter.incrementAndGet(), action))
    }

    fun soon(action: () -> Unit) = at(System.nanoTime(), action)

    override fun close() {
        running = false
        thread.join(1000)
    }
}

/**
 * An in-memory "internet": pipes deliver in order with [latencyMs] plus up to
 * [jitterMs] of random delay (never reordering), all callbacks on [sched].
 */
internal class MemNet(private val latencyMs: Long = 0, private val jitterMs: Long = 0, seed: Int = 1) : AutoCloseable {
    val sched = MemScheduler("mem-transport")
    /** Mimic a transport that holds one lock both in send() and while calling handlers. */
    @Volatile var lockedTransport = false
    private val transportLock = Any()
    private fun locked(action: () -> Unit) = if (lockedTransport) synchronized(transportLock, action) else action()
    private val random = Random(seed)
    val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile var acceptor: ((Long, Pipe) -> PipeHandler?)? = null
    val connects = AtomicLong()

    private fun delayNs(): Long {
        val jitter = if (jitterMs > 0) synchronized(random) { random.nextLong(jitterMs * 1_000_000) } else 0
        return latencyMs * 1_000_000 + jitter
    }

    inner class End(val label: String) : Pipe {
        lateinit var peer: End
        @Volatile var handler: PipeHandler? = null
        @Volatile var closed = false // this side called close, or saw onClosed
        private var lastDue = 0L
        val sent = AtomicLong()
        override val path get() = "mem:$label"

        override fun send(message: ByteArray) = locked {
            if (message.size > Pipe.MAX_MESSAGE) errors.add("$label sent ${message.size} bytes")
            if (!closed) {
                sent.incrementAndGet()
                val copy = message.copyOf()
                deliver { if (!peer.closed) peer.handler?.onMessage(peer, copy) }
            }
        }

        override fun close(reason: String) {
            if (closed) return
            closed = true
            deliver { peer.closedByOther(reason) }
        }

        fun closedByOther(reason: String) {
            if (closed) return
            closed = true
            handler?.onClosed(this, reason)
        }

        // Never earlier than the previous message: a reliable stream doesn't reorder.
        private fun deliver(action: () -> Unit) = synchronized(this) {
            val due = maxOf(lastDue, System.nanoTime() + delayNs())
            lastDue = due
            sched.at(due) { locked(action) }
        }
    }

    fun pair(a: String, b: String): Pair<End, End> {
        val x = End(a)
        val y = End(b)
        x.peer = y
        y.peer = x
        return x to y
    }

    /** The network drops a connection: both sides hear about it. */
    fun cut(end: End, reason: String) = sched.soon {
        end.closedByOther(reason)
        end.peer.closedByOther(reason)
    }

    fun hostTransport(): Transport = object : FakeTransport() {
        override fun setAcceptor(acceptor: (connId: Long, pipe: Pipe) -> PipeHandler?) {
            this@MemNet.acceptor = acceptor
        }
    }

    fun guestTransport(): FakeTransport = object : FakeTransport() {
        override fun connect(connId: Long, hostCandidates: List<Candidate>, handler: PipeHandler, onOpen: (Pipe) -> Unit) {
            connects.incrementAndGet()
            sched.at(System.nanoTime() + delayNs()) {
                if (closed) return@at
                val (g, h) = pair("guest$connId", "host<-$connId")
                g.handler = handler
                val hostHandler = acceptor?.invoke(connId, h)
                if (hostHandler == null) {
                    handler.onClosed(g, "no answer from the host")
                    return@at
                }
                h.handler = hostHandler
                pipes.add(g)
                onOpen(g)
            }
        }
    }

    abstract inner class FakeTransport : Transport {
        @Volatile var closed = false
        val pipes: MutableList<End> = Collections.synchronizedList(ArrayList())
        override val port = 40000
        override fun setAcceptor(acceptor: (connId: Long, pipe: Pipe) -> PipeHandler?) {
            error("guest transport")
        }
        override fun probe(connId: Long, candidates: List<Candidate>) {}
        override fun connect(connId: Long, hostCandidates: List<Candidate>, handler: PipeHandler, onOpen: (Pipe) -> Unit) {
            error("host transport")
        }
        override fun stun(server: InetSocketAddress, timeoutMs: Int): InetSocketAddress? = null
        override fun close() {
            closed = true
        }
    }

    override fun close() = sched.close()
}

/** Records everything a link backend reports. */
internal class LinkRecorder(val label: String) : LinkListener {
    val frames: MutableList<String> = Collections.synchronizedList(ArrayList()) // hex, for easy comparison
    val statuses: MutableList<String> = Collections.synchronizedList(ArrayList())
    val left: MutableList<String> = Collections.synchronizedList(ArrayList())
    val joins: MutableList<Triple<String, Int, List<RoomPlayer>>> = Collections.synchronizedList(ArrayList())
    @Volatile var fatal: String? = null
    @Volatile var lost: String? = null
    @Volatile var badFrame = false

    val linkId get() = joins.lastOrNull()?.second ?: -1
    val players get() = joins.lastOrNull()?.third ?: emptyList()

    override fun onStatus(text: String) {
        statuses.add(text)
    }

    override fun onJoined(roomName: String, linkId: Int, players: List<RoomPlayer>) {
        joins.add(Triple(roomName, linkId, players))
    }

    override fun onLinkFrame(frame: ByteArray) {
        if (!Protocol.validFrame(frame)) badFrame = true
        frames.add(frame.hex())
    }

    override fun onPartnerLeft(name: String) {
        left.add(name)
    }

    override fun onSessionLost(reason: String) {
        lost = reason
    }

    override fun onFatal(message: String) {
        fatal = message
    }

    fun snapshot(): List<String> = synchronized(frames) { frames.toList() }

    /** Commands from [player]'s slot in every entry, in order (word 0 of each non-empty slot). */
    fun commandsFrom(player: Int): List<Int> = snapshot().map { it.unhex() }.filter { it[0].toInt() == 2 }.mapNotNull { f ->
        val w0 = Protocol.u16(f, 2 + player * 16)
        if (w0 == 0) null else w0
    }

    fun handshakes(): List<Pair<Int, Int>> = snapshot().map { it.unhex() }.filter { it[0].toInt() == 1 }
        .map { it[1].toInt() to Protocol.u16(it, 2) }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.unhex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** GBA -> link payloads as Native.netPoll returns them. */
internal fun gbaHandshake(word: Int) = byteArrayOf(1, 0, word.toByte(), (word shr 8).toByte())

internal fun gbaCommand(words: IntArray): ByteArray {
    val b = ByteArray(18)
    b[0] = 2
    for (i in 0 until 8) {
        b[2 + i * 2] = words[i].toByte()
        b[3 + i * 2] = (words[i] shr 8).toByte()
    }
    return b
}

internal fun waitUntil(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
        Thread.sleep(5)
    }
}
