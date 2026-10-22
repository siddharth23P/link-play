package com.linkplay.app.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicLong

/** Shared bits of the UdpTransport tests. Everything binds loopback, so macOS's firewall never asks. */
internal object UdpTestKit {
    val V4: InetAddress = InetAddress.getByName("127.0.0.1")
    val V6: InetAddress = InetAddress.getByName("::1")
    val random = SecureRandom()

    fun newKey() = RoomKey(random.nextLong(), ByteArray(16).also { random.nextBytes(it) })

    /** Set LINKPLAY_TEST_LOG=1 to see the transports' logs. */
    fun opts(bind: InetAddress = V4) = TransportOptions(
        bindAddress = bind,
        log = if (System.getenv("LINKPLAY_TEST_LOG") == "1") { s -> println("  [udp] $s") } else { _ -> },
    )

    fun cand(ip: InetAddress, port: Int, kind: Candidate.Kind = Candidate.Kind.LAN) = Candidate(InetSocketAddress(ip, port), kind)

    fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    /** The type of a packet a transport sent (without the host's side bit), opened with the room key; -1 if it does not open. */
    fun typeOf(crypto: PacketCrypto, packet: ByteArray): Int = crypto.open(packet, packet.size)?.let { it[0].toInt() and 0x7f } ?: -1

    /** True if a transport's packet says the host sent it; null if it does not open. */
    fun fromHost(crypto: PacketCrypto, packet: ByteArray): Boolean? = crypto.open(packet, packet.size)?.let { it[0].toInt() and 0x80 != 0 }
}

/** Records what a pipe's handler sees. */
internal class UdpTestPeer : PipeHandler {
    val messages = LinkedBlockingQueue<ByteArray>()
    val closed = CompletableFuture<String>()
    @Volatile var hook: ((Pipe, ByteArray) -> Unit)? = null

    override fun onMessage(pipe: Pipe, message: ByteArray) {
        val h = hook
        if (h != null) h(pipe, message) else messages.add(message)
    }

    override fun onClosed(pipe: Pipe, reason: String) {
        closed.complete(reason)
    }

    fun next(timeoutMs: Long = 2000): ByteArray =
        messages.poll(timeoutMs, MILLISECONDS) ?: throw AssertionError("no message within $timeoutMs ms")

    fun nextText(timeoutMs: Long = 2000) = String(next(timeoutMs))

    fun closedReason(timeoutMs: Long = 3000): String = closed.get(timeoutMs, MILLISECONDS)
}

/** A host and a guest transport of one room, and their handlers. */
internal class UdpTestPair(
    val key: RoomKey = UdpTestKit.newKey(),
    hostOptions: TransportOptions = UdpTestKit.opts(),
    guestOptions: TransportOptions = UdpTestKit.opts(),
) : AutoCloseable {
    val host = UdpTransport(key, true, hostOptions)
    val guest = UdpTransport(key, false, guestOptions)
    val hostPeer = UdpTestPeer()
    val guestPeer = UdpTestPeer()
    val hostPipe = CompletableFuture<Pipe>()
    val guestPipe = CompletableFuture<Pipe>()
    val accepted = ConcurrentLinkedQueue<Long>()
    val connId = UdpTestKit.random.nextLong()

    init {
        host.setAcceptor { id, pipe ->
            accepted.add(id)
            if (id == connId) hostPipe.complete(pipe)
            if (id == connId) hostPeer else null
        }
    }

    fun start(candidates: List<Candidate> = listOf(UdpTestKit.cand(UdpTestKit.V4, host.port))) =
        guest.connect(connId, candidates, guestPeer) { guestPipe.complete(it) }

    /** (guest's pipe, host's pipe) once both are open. */
    fun awaitOpen(timeoutMs: Long = 3000): Pair<Pipe, Pipe> =
        guestPipe.get(timeoutMs, MILLISECONDS) to hostPipe.get(timeoutMs, MILLISECONDS)

    fun connect(candidates: List<Candidate> = listOf(UdpTestKit.cand(UdpTestKit.V4, host.port)), timeoutMs: Long = 3000): Pair<Pipe, Pipe> {
        start(candidates)
        return awaitOpen(timeoutMs)
    }

    override fun close() {
        guest.close()
        host.close()
    }
}

/**
 * A UDP middlebox between one client and [target]: the client sends to [address], we forward from an
 * upstream socket (which [rebind] swaps, like a NAT that changes its port) and relay the answers back.
 * Every datagram may be lost, duplicated and delayed by delayMs +- jitterMs (which also reorders them).
 */
internal class UdpTestProxy(private val target: InetSocketAddress, private val bind: InetAddress = UdpTestKit.V4) : AutoCloseable {
    @Volatile var loss = 0.0
    @Volatile var duplicate = 0.0
    @Volatile var delayMs = 0
    @Volatile var jitterMs = 0
    val forwarded = AtomicLong()
    val dropped = AtomicLong()

    private val random = Random(42)
    private val front = socket()
    @Volatile private var upstream = socket()
    @Volatile private var client: InetSocketAddress? = null
    @Volatile private var running = true
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "proxy-timer").apply { isDaemon = true } }

    val address get() = InetSocketAddress(bind, front.localPort)
    val upstreamPort get() = upstream.localPort

    init {
        pump(front, toTarget = true)
        pump(upstream, toTarget = false)
    }

    /** From now on, forward from a new port; the old one is gone. */
    fun rebind() {
        val old = upstream
        upstream = socket().also { pump(it, toTarget = false) }
        old.close()
    }

    private fun socket() = DatagramSocket(InetSocketAddress(bind, 0)).apply { receiveBufferSize = 1 shl 20 }

    private fun pump(s: DatagramSocket, toTarget: Boolean) = Thread({
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (running && !s.isClosed) {
            try {
                p.setData(buf)
                s.receive(p)
            } catch (e: IOException) {
                break
            }
            val data = buf.copyOf(p.length)
            if (toTarget) {
                client = p.socketAddress as InetSocketAddress
                impair(data) { send(upstream, it, target) } // upstream as of sending time
            } else {
                val c = client ?: continue
                impair(data) { send(front, it, c) }
            }
        }
    }, "proxy-pump").apply { isDaemon = true; start() }

    private fun impair(data: ByteArray, out: (ByteArray) -> Unit) {
        val delays = synchronized(random) {
            if (random.nextDouble() < loss) null
            else List(if (random.nextDouble() < duplicate) 2 else 1) {
                (delayMs + if (jitterMs > 0) random.nextInt(2 * jitterMs + 1) - jitterMs else 0).coerceAtLeast(0)
            }
        }
        if (delays == null) {
            dropped.incrementAndGet()
            return
        }
        for (d in delays) {
            if (d == 0) out(data) else try {
                timer.schedule({ out(data) }, d.toLong(), MILLISECONDS)
            } catch (e: Exception) { // shut down
            }
        }
    }

    private fun send(s: DatagramSocket, data: ByteArray, to: InetSocketAddress) {
        try {
            s.send(DatagramPacket(data, data.size, to))
            forwarded.incrementAndGet()
        } catch (e: IOException) {
        }
    }

    override fun close() {
        running = false
        timer.shutdownNow()
        front.close()
        upstream.close()
    }
}
