package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import com.linkplay.app.net.UdpTestKit.cand
import com.linkplay.app.net.UdpTestKit.newKey
import com.linkplay.app.net.UdpTestKit.opts
import com.linkplay.app.net.UdpTestKit.waitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adversarial review of UdpTransport. Each test states the behaviour the transport SHOULD have;
 * a failure is the evidence for the bug named in its comment.
 */
class UdpTransportReviewTest {
    private fun Pipe.sendText(s: String) = send(s.toByteArray())

    /** Plaintext of a packet one of the transports sent: u8 type, u64 connId, u64 counter, body. */
    private class Plain(val type: Int, val counter: Long, val ack: Int, val count: Int)

    private fun plain(crypto: PacketCrypto, p: ByteArray): Plain? {
        val b = ByteBuffer.wrap(crypto.open(p, p.size) ?: return null)
        val type = b.get().toInt() and 0x7f // (a fix may mark the sender's side in the top bit)
        b.long
        val counter = b.long
        val data = type == UdpTransport.DATA && b.remaining() >= 5
        return Plain(type, counter, if (data) b.int else -1, if (data) b.get().toInt() and 0xff else -1)
    }

    private fun DatagramSocket.sendTo(p: ByteArray, port: Int) = send(DatagramPacket(p, p.size, InetSocketAddress(V4, port)))

    /** Datagrams [s] receives within [ms]. */
    private fun DatagramSocket.drain(ms: Int): Int {
        soTimeout = 50
        val end = System.nanoTime() + ms * 1_000_000L
        val p = DatagramPacket(ByteArray(2048), 2048)
        var n = 0
        while (System.nanoTime() < end) try {
            receive(p)
            n++
        } catch (e: SocketTimeoutException) {
        }
        return n
    }

    // ------------------------------------------------------------------ STUN demux

    /**
     * A "binding success" whose length field claims exactly the 2048-byte receive buffer and whose last
     * attribute header (XOR-MAPPED-ADDRESS, length 0) ends at byte 2048: Stun.address reads p[at + 1]
     * = p[2049] before checking alen. Any datagram longer than the buffer is truncated to 2048 bytes.
     */
    private fun stunBomb(): ByteArray {
        val b = ByteBuffer.allocate(2100)
        b.putShort(0x0101).putShort((2048 - 20).toShort()).putInt(Stun.MAGIC_COOKIE).put(ByteArray(12))
        b.putShort(0x8000.toShort()).putShort((2044 - 24).toShort()).put(ByteArray(2044 - 24)) // an unknown attribute as filler
        b.putShort(0x0020).putShort(0) // XOR-MAPPED-ADDRESS with no value, header ends at 2048
        return b.array()
    }

    /** BUG (critical): the parser indexes past the buffer instead of returning null. */
    @Test
    fun stunParserRejectsAnAttributeAtTheVeryEndOfTheBuffer() {
        val buf = stunBomb().copyOf(2048) // what receiveLoop's 2048-byte buffer holds
        assertTrue(Stun.isStun(buf, buf.size))
        val r = try {
            Stun.parseResponse(buf, buf.size)
        } catch (e: RuntimeException) {
            throw AssertionError("parseResponse threw instead of rejecting: $e", e)
        }
        assertNull(r?.mapped)
    }

    /**
     * BUG (critical): one unauthenticated datagram from anyone who knows the port (it is in the invite)
     * kills the receive thread: receiveLoop only catches IOException, so the AIOOBE escapes. Every later
     * packet is ignored and the pipes die of "timeout". On Android an uncaught exception in any thread
     * kills the whole app process.
     */
    @Test
    fun oneOversizedStunLookalikeDoesNotKillTheReceiver() {
        val uncaught = ConcurrentLinkedQueue<String>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e -> uncaught.add("${th.name}: $e") }
        try {
            UdpTestPair().use { t ->
                val (g, h) = t.connect()
                g.sendText("before")
                assertEquals("before", t.hostPeer.nextText())
                DatagramSocket(InetSocketAddress(V4, 0)).use { s -> s.sendTo(stunBomb(), t.host.port) }
                Thread.sleep(300)
                g.sendText("after")
                h.sendText("back")
                val atHost = t.hostPeer.messages.poll(2000, MILLISECONDS)?.let { String(it) }
                assertTrue("uncaught exceptions: $uncaught; host got after the bomb: $atHost", uncaught.isEmpty() && atHost == "after")
                assertEquals("back", t.guestPeer.nextText()) // (the guest's receiver was not attacked)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    // ------------------------------------------------------------------ reflection (no direction in packets)

    /**
     * BUG (major): both directions use the same key and nothing in a packet says who sent it, so the
     * host's own DATA, captured on the path and sent back to the host, opens fine and is processed as the
     * guest's. Here it delivers the host's own message to the host's handler as if the guest had sent it,
     * and the guest's real message with that sequence number is then thrown away as a duplicate.
     */
    @Test
    fun hostDoesNotTakeItsOwnReflectedDataAsTheGuests() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val hostSent = ConcurrentLinkedQueue<ByteArray>()
        UdpTestPair(key, hostOptions = opts().copy(outboundFilter = { p, _ -> hostSent.add(p); true })).use { t ->
            val (g, h) = t.connect()
            h.sendText("from the host")
            assertEquals("from the host", t.guestPeer.nextText())
            val captured = hostSent.first { plain(crypto, it)?.let { p -> p.type == UdpTransport.DATA && p.count > 0 } == true }
            DatagramSocket(InetSocketAddress(V4, 0)).use { attacker -> attacker.sendTo(captured, t.host.port) }
            val bogus = t.hostPeer.messages.poll(500, MILLISECONDS)?.let { String(it) }
            g.sendText("from the guest")
            val real = t.hostPeer.messages.poll(2000, MILLISECONDS)?.let { String(it) }
            assertTrue("host's handler got its own message: '$bogus'; the guest's real message: '$real'", bogus == null && real == "from the guest")
        }
    }

    /**
     * BUG (major): the reflected packet's ack field (what the HOST expects from the guest) is applied to the
     * host's own send queue, so messages the guest never got are dropped from it and never resent. The
     * guest's stream then stalls for good (later messages wait behind the hole until "too far behind").
     */
    @Test
    fun reflectedAckDoesNotDropMessagesTheGuestNeverGot() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val hostSent = ConcurrentLinkedQueue<ByteArray>()
        val deaf = AtomicBoolean()
        UdpTestPair(
            key,
            hostOptions = opts().copy(outboundFilter = { p, _ -> hostSent.add(p); true }),
            guestOptions = opts().copy(inboundFilter = { _, _ -> !deaf.get() }),
        ).use { t ->
            val (g, h) = t.connect()
            repeat(3) { g.sendText("g$it") }
            repeat(3) { assertEquals("g$it", t.hostPeer.nextText()) } // host now acks 4
            deaf.set(true) // the guest's radio drops out for a moment
            hostSent.clear()
            repeat(3) { h.sendText("h$it") } // host seq 1..3, so its packets carry ack 4 <= its next seq 4
            waitUntil(1000, "a host DATA carrying ack 4") { hostSent.any { plain(crypto, it)?.ack == 4 } }
            val captured = hostSent.first { plain(crypto, it)?.ack == 4 }
            DatagramSocket(InetSocketAddress(V4, 0)).use { attacker -> attacker.sendTo(captured, t.host.port) }
            Thread.sleep(300)
            deaf.set(false)
            h.sendText("h3")
            val got = List(4) { t.guestPeer.messages.poll(2000, MILLISECONDS)?.let { String(it) } }
            assertEquals("the guest gets everything once its radio is back", listOf("h0", "h1", "h2", "h3"), got)
        }
    }

    /**
     * BUG (major): a reflected host packet carries the HOST's counter, which is usually far above the
     * guest's (the host talks more), so it is "fresh": the host moves the guest's path to the reflector,
     * and since rxHighest now holds the host's counter, the guest's real packets are stale and can neither
     * move the path back nor count as signs of life. One datagram cuts host -> guest traffic until the
     * guest's counter catches up or the pipe times out.
     */
    @Test
    fun reflectedHostPacketDoesNotHijackThePath() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val hostSent = ConcurrentLinkedQueue<ByteArray>()
        UdpTestPair(key, hostOptions = opts().copy(outboundFilter = { p, _ -> hostSent.add(p); true })).use { t ->
            val (g, h) = t.connect()
            g.sendText("hi")
            assertEquals("hi", t.hostPeer.nextText())
            val path = h.path
            repeat(100) { h.send(byteArrayOf(it.toByte())); Thread.sleep(3) } // e.g. link frames: host counter runs ahead
            repeat(100) { t.guestPeer.next() }
            val latest = hostSent.filter { plain(crypto, it)?.type == UdpTransport.DATA }.maxByOrNull { plain(crypto, it)!!.counter }!!
            DatagramSocket(InetSocketAddress(V4, 0)).use { attacker ->
                attacker.sendTo(latest, t.host.port)
                Thread.sleep(200)
                h.sendText("after")
                val got = t.guestPeer.messages.poll(2000, MILLISECONDS)?.let { String(it) }
                val atAttacker = attacker.drain(300)
                assertTrue(
                    "host path '${h.path}' (was '$path'); guest got '$got'; attacker received $atAttacker datagrams",
                    h.path == path && got == "after",
                )
            }
        }
    }

    /**
     * BUG (major, DoS): every authenticated datagram becomes a task on an unbounded queue, and every
     * HELLO, even a stale replay, costs the worker a seal + send (a WELCOME to the replayer). One captured
     * HELLO replayed as fast as possible outruns the worker, so the queue grows for as long as the flood
     * lasts (measured ~500k tasks / +370 MB heap in ~20 s on this Mac: an OOM crash on a phone) and the
     * real guest's packets sit behind it, so the game stalls and the pipe eventually dies of "timeout".
     */
    @Test
    fun replayedHelloFloodDoesNotBuryRealTraffic() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val guestSent = ConcurrentLinkedQueue<ByteArray>()
        UdpTestPair(key, guestOptions = opts().copy(outboundFilter = { p, _ -> guestSent.add(p); true })).use { t ->
            val (g, _) = t.connect()
            val hello = guestSent.first { plain(crypto, it)?.type == UdpTransport.HELLO }
            val stop = AtomicBoolean()
            val flooders = List(4) {
                Thread {
                    DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
                        val p = DatagramPacket(hello, hello.size, InetSocketAddress(V4, t.host.port))
                        while (!stop.get()) s.send(p)
                    }
                }.apply { start() }
            }
            try {
                Thread.sleep(2000)
                val start = System.nanoTime()
                g.sendText("ping")
                val got = t.hostPeer.messages.poll(3000, MILLISECONDS)?.let { String(it) } // the flood goes on meanwhile
                val ms = (System.nanoTime() - start) / 1_000_000
                val queued = runCatching {
                    (UdpTransport::class.java.getDeclaredField("tasks").apply { isAccessible = true }.get(t.host) as Collection<*>).size
                }.getOrNull()
                println("HELLO replay flood: $queued worker tasks queued; 'ping' ${if (got != null) "arrived after $ms ms" else "not delivered within 3 s"}")
                // (queued is null if a fix renames the private queue; then only the delivery is checked)
                assertTrue("worker tasks queued: $queued; 'ping' delivered during the flood: $got ($ms ms)", got == "ping" && (queued ?: 0) < 10_000)
            } finally {
                stop.set(true)
                flooders.forEach { it.join() }
            }
        }
    }

    // ------------------------------------------------------------------ replays while connecting

    /**
     * BUG (minor): while CONNECTING, WELCOME is not checked for freshness and an address that is not in
     * the invite gets an inferred kind (loopback / private -> LAN, the best rank). A stale copy of the
     * host's WELCOME replayed from another address therefore wins the path choice (contradicting "a packet
     * with an old counter never moves the path"); it only heals when the host's next DATA arrives.
     */
    @Test
    fun staleWelcomeReplayDoesNotChooseTheGuestsPath() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val attacker = DatagramSocket(InetSocketAddress(V4, 0))
        val replayed = AtomicBoolean()
        var guestPort = 0
        val hostOpts = opts().copy(outboundFilter = { p, _ ->
            if (plain(crypto, p)?.type == UdpTransport.WELCOME && replayed.compareAndSet(false, true)) {
                Thread { Thread.sleep(50); attacker.sendTo(p, guestPort) }.start() // same packet, 50 ms later
            }
            true
        })
        DatagramSocket(InetSocketAddress(V4, 0)).use { silentLan ->
            UdpTestPair(key, hostOptions = hostOpts).use { t ->
                guestPort = t.guest.port
                // A LAN candidate that never answers keeps the 300 ms settle window open after the mapped WELCOME.
                t.start(listOf(cand(V4, t.host.port, Candidate.Kind.MAPPED), cand(V4, silentLan.localPort, Candidate.Kind.LAN)))
                val (g, _) = t.awaitOpen()
                assertTrue("guest path ${g.path}, attacker port ${attacker.localPort}", g.path.startsWith("127.0.0.1:${t.host.port} "))
            }
        }
        attacker.close()
    }

    // ------------------------------------------------------------------ connect() contract

    /**
     * BUG (minor): Transport.connect promises onOpen or handler.onClosed. A connId that already finished on
     * this transport (e.g. "Retry" after "could not reach the host", keeping the reply code the host already
     * has) is silently ignored (tombs.containsKey -> return), so the second handler never hears anything.
     */
    @Test
    fun retryingAConnIdReportsAnOutcome() = DatagramSocket(InetSocketAddress(V4, 0)).use { silent ->
        val guest = UdpTransport(newKey(), false, opts().copy(connectTimeoutMs = 300))
        try {
            val first = UdpTestPeer()
            guest.connect(42, listOf(cand(V4, silent.localPort)), first) {}
            assertEquals("could not reach the host", first.closedReason(2000))
            val retry = UdpTestPeer()
            guest.connect(42, listOf(cand(V4, silent.localPort)), retry) {}
            val outcome = try {
                retry.closedReason(2000)
            } catch (e: TimeoutException) {
                null
            }
            assertTrue("the retry's handler heard nothing at all", outcome != null)
        } finally {
            guest.close()
        }
    }
}
