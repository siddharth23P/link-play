package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The host's socket is reachable from the whole internet: garbage and forgeries must be cheap to throw
 * away, limited per source, and must neither slow a real guest down nor make the transport remember
 * anything per sender beyond a bounded table.
 */
class StrangerFloodTest {
    private val ms = 1_000_000L

    // ---------------------------------------------------------------- SourceLimiter on its own

    @Test
    fun aSourceGetsABurstThenItsRate() {
        val l = SourceLimiter(50.0, 100, 1024)
        val a = InetAddress.getByName("203.0.113.9")
        val t0 = 1_000_000_000L
        assertTrue("never seen: allowed", l.allows(a, t0))
        assertEquals("allowing remembers nothing", 0, l.size())
        repeat(100) { l.charge(a, t0) }
        assertFalse("burst spent", l.allows(a, t0))
        assertFalse("19 ms later: not even one token", l.allows(a, t0 + 19 * ms))
        assertTrue("21 ms later: one token (50 a second)", l.allows(a, t0 + 21 * ms))
        l.charge(a, t0 + 21 * ms)
        assertFalse(l.allows(a, t0 + 22 * ms))
        assertTrue("others are not affected", l.allows(InetAddress.getByName("203.0.113.10"), t0))
        assertTrue("refilled after 2 s", l.allows(a, t0 + 2_100 * ms))
        repeat(99) { l.charge(a, t0 + 10_000 * ms) }
        assertTrue("a full burst again, never more", l.allows(a, t0 + 10_000 * ms))
        l.charge(a, t0 + 10_000 * ms)
        assertFalse(l.allows(a, t0 + 10_000 * ms))
    }

    @Test
    fun anIpv6SourceIsItsSlash64() {
        val l = SourceLimiter(50.0, 3, 1024)
        val t0 = 0L
        repeat(3) { l.charge(InetAddress.getByName("2401:4900:1c2e:8a1f::$it"), t0) }
        // Another address in the same /64 (a phone's next privacy address): same bucket, empty.
        assertFalse(l.allows(InetAddress.getByName("2401:4900:1c2e:8a1f:aaaa:bbbb:cccc:dddd"), t0))
        assertTrue(l.allows(InetAddress.getByName("2401:4900:1c2e:8a20::1"), t0))
        assertTrue(l.allows(InetAddress.getByName("49.36.1.2"), t0))
        assertEquals(1, l.size())
        // IPv4 keys never collide with IPv6 ones, nor with each other.
        val keys = listOf("0.0.0.1", "255.255.255.255", "::1", "2001:db8::", "ffff:fffe::1").map { SourceLimiter.key(InetAddress.getByName(it)) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun theTableOfSourcesStaysBoundedAndKeepsTheFlooderLimited() {
        val l = SourceLimiter(50.0, 100, 1024)
        val flooder = InetAddress.getByName("198.51.100.66")
        val t0 = 5_000_000_000L
        repeat(100) { l.charge(flooder, t0) }
        val r = Random(7)
        for (i in 0 until 20_000) {
            // A spray from 20 000 sources, IPv4 and IPv6 /64s; the flooder keeps sending all along.
            val ip = if (i % 2 == 0) InetAddress.getByAddress(ByteArray(4).also { r.nextBytes(it) })
                     else InetAddress.getByAddress(ByteArray(16).also { r.nextBytes(it); it[0] = 0x24 })
            l.charge(ip, t0)
            assertFalse("the flooder stays limited (seen recently, so never forgotten)", l.allows(flooder, t0))
            assertTrue(l.size() <= 1024)
        }
        assertEquals(1024, l.size())
    }

    /** However many sources a flood uses, all strangers together get the total allowance, no more. */
    @Test
    fun allStrangersTogetherAreHeldToTheTotal() {
        val l = SourceLimiter(50.0, 100, 1024, 500.0, 1000)
        val sources = List(600) { InetAddress.getByAddress(byteArrayOf(10, (it shr 8).toByte(), it.toByte(), 1)) }
        val t0 = 1_000_000_000L
        var allowed = 0
        for (round in 0 until 100) { // 2 s, each source at its own rate (50 a second): only the total stops them
            val now = t0 + round * 20 * ms
            for (s in sources) if (l.allows(s, now)) {
                allowed++
                l.charge(s, now)
            }
        }
        assertTrue("$allowed decrypted in 2 s", allowed in 1000..(1000 + 500 * 2 + 10))
        assertFalse("the total is spent", l.allows(InetAddress.getByName("192.0.2.1"), t0 + 99 * 20 * ms))
        assertTrue("and earned back", l.allows(InetAddress.getByName("192.0.2.1"), t0 + 3_000 * ms))
    }

    /**
     * A full table forgets a source only once it has all its tokens back. Until then a new source isn't let
     * in (it could be one that was forgotten while it still owed us); afterwards it is, as before.
     */
    @Test
    fun aFullTableTakesNewSourcesAgainOnceItsOldestHasItsTokensBack() {
        val l = SourceLimiter(50.0, 100, 4, 1e9, 1_000_000)
        val t0 = 1_000_000_000L
        val old = List(4) { InetAddress.getByName("203.0.113.${it + 1}") }
        for (a in old) repeat(100) { l.charge(a, t0) }
        val newcomer = InetAddress.getByName("198.51.100.7")
        assertFalse("the table is full of sources that owe us", l.allows(newcomer, t0))
        l.charge(newcomer, t0) // e.g. let in just before the table filled: paid for, but nothing is forgotten
        assertEquals(4, l.size())
        for (a in old) assertFalse("nobody was forgotten", l.allows(a, t0))
        assertFalse("half refilled is not enough", l.allows(newcomer, t0 + 1_000 * ms))
        assertTrue("all refilled: forgetting the oldest loses nothing", l.allows(newcomer, t0 + 2_100 * ms))
        l.charge(newcomer, t0 + 2_100 * ms)
        assertEquals(4, l.size())
        repeat(99) { l.charge(newcomer, t0 + 2_100 * ms) }
        assertFalse("the newcomer has a bucket of its own now", l.allows(newcomer, t0 + 2_100 * ms))
    }

    /**
     * Next to a live peer (its IP, another port), sources are counted per port and from a pool of their own:
     * a flood from elsewhere doesn't stop the peer after its NAT moved it, and junk from one port of its IP
     * doesn't stop another port.
     */
    @Test
    fun sourcesNextToALivePeerAreCountedPerPortFromAPoolOfTheirOwn() {
        val l = SourceLimiter(50.0, 100, 1024, 500.0, 1000)
        val t0 = 1_000_000_000L
        for (i in 0 until 10) repeat(100) { l.charge(InetAddress.getByName("2001:db8:$i::1"), t0) }
        assertFalse("strangers' total spent", l.allows(InetAddress.getByName("2001:db8:ff::1"), t0))
        val peerIp = InetAddress.getByName("100.64.1.2")
        val ipKey = SourceLimiter.key(peerIp)
        val flooder = SourceLimiter.portKey(ipKey, InetSocketAddress(peerIp, 40000))
        val moved = SourceLimiter.portKey(ipKey, InetSocketAddress(peerIp, 40001))
        assertTrue(flooder != moved && flooder != ipKey && moved != ipKey)
        assertTrue("its own pool", l.allows(flooder, true, t0))
        repeat(100) { l.charge(flooder, true, t0) }
        assertFalse(l.allows(flooder, true, t0))
        assertTrue("another port of the same IP is not affected", l.allows(moved, true, t0))
        val v6 = InetAddress.getByName("2401:4900:1c2e:8a1f::5")
        assertTrue("IPv6 ports differ too", SourceLimiter.portKey(SourceLimiter.key(v6), InetSocketAddress(v6, 1)) !=
            SourceLimiter.portKey(SourceLimiter.key(v6), InetSocketAddress(v6, 2)))
    }

    // ---------------------------------------------------------------- over the real transport

    /** [n] numbered pings from the guest, [gapMs] apart; the host echoes them. Round trips in ms, by ping. */
    private fun roundTrips(t: UdpTestPair, g: Pipe, n: Int, gapMs: Long, tag: Int): List<Double> {
        val sentAt = LongArray(n)
        val rtt = DoubleArray(n) { -1.0 }
        t.guestPeer.hook = { _, m ->
            val b = ByteBuffer.wrap(m)
            if (m.size == 8 && b.int == tag) b.int.let { i -> rtt[i] = (System.nanoTime() - sentAt[i]) / 1e6 }
        }
        for (i in 0 until n) {
            sentAt[i] = System.nanoTime()
            g.send(ByteBuffer.allocate(8).putInt(tag).putInt(i).array())
            Thread.sleep(gapMs)
        }
        UdpTestKit.waitUntil(5000, "all $n echoes (${rtt.count { it >= 0 }} so far)") { rtt.all { it >= 0 } }
        return rtt.toList().sorted()
    }

    private fun List<Double>.pct(p: Int) = this[(size - 1) * p / 100]

    @Test
    fun garbageFloodFromOneAddressDoesNotDelayARealGuest() = UdpTestPair().use { t ->
        val (g, _) = t.connect()
        t.hostPeer.hook = { p, m -> p.send(m) } // echo
        val calm = roundTrips(t, g, 40, 5, 1)

        val stop = AtomicBoolean()
        val sent = AtomicLong()
        val flood = Thread {
            DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
                // Shaped like ours (magic, version, a plausible size), so each one would cost a decryption.
                val r = Random(1)
                val junk = List(64) {
                    ByteArray(UdpTransport.MIN_PACKET + r.nextInt(UdpTransport.MAX_PACKET - UdpTransport.MIN_PACKET + 1))
                        .also { p -> r.nextBytes(p); p[0] = PacketCrypto.MAGIC; p[1] = PacketCrypto.VERSION }
                }
                val to = InetSocketAddress(V4, t.host.port)
                var i = 0
                while (!stop.get()) {
                    val p = junk[i++ and 63]
                    s.send(DatagramPacket(p, p.size, to))
                    sent.incrementAndGet()
                }
            }
        }
        val floodStart = System.nanoTime()
        flood.start()
        try {
            Thread.sleep(300) // its burst is long spent
            val during = roundTrips(t, g, 150, 5, 2)
            stop.set(true)
            flood.join()
            val secs = (System.nanoTime() - floodStart) / 1e9
            val decrypted = t.host.authFailures.get()
            println("garbage flood: %d datagrams in %.1f s, %d decrypted, %d dropped unread; round trips calm p50 %.2f / p90 %.2f ms, flooded p50 %.2f / p90 %.2f / max %.2f ms"
                .format(sent.get(), secs, decrypted, t.host.limited.get(), calm.pct(50), calm.pct(90), during.pct(50), during.pct(90), during.last()))
            assertTrue("decrypted $decrypted in %.1f s".format(secs), decrypted <= 100 + 50 * secs + 10)
            assertTrue("the flood was mostly dropped unread", t.host.limited.get() > 10 * decrypted)
            // Not delayed: the real guest's round trips stay those of a calm loopback (allowing for a busy CPU).
            assertTrue("flooded p90 ${during.pct(90)} ms", during.pct(90) < maxOf(25.0, 5 * calm.pct(90)))
            assertTrue("flooded p50 ${during.pct(50)} ms", during.pct(50) < maxOf(10.0, 5 * calm.pct(50)))
            assertFalse(t.hostPeer.closed.isDone || t.guestPeer.closed.isDone)
            val sizes = t.host.tableSizes()
            // Two sources: 127.0.0.1 (the guest's first HELLO, before it had a pipe) and the flooder's port
            // (next to the guest's address, so counted per port).
            assertEquals("one pipe, no probes, no tombs, two sources: $sizes", listOf(1, 0, 0, 2), listOf(sizes["conns"], sizes["probes"], sizes["tombs"], sizes["sources"]))
        } finally {
            stop.set(true)
            flood.join()
        }
    }

    /** The transport applies the strangers' total too (here far below one source's own allowance). */
    @Test
    fun aFloodIsHeldToTheStrangersTotal() {
        val host = UdpTransport(UdpTestKit.newKey(), true, UdpTestKit.opts().copy(
            strangerPerSecond = 1e6, strangerBurst = 1_000_000, strangerTotalPerSecond = 100.0, strangerTotalBurst = 200))
        try {
            val r = Random(11)
            val junk = List(16) { ByteArray(200).also { p -> r.nextBytes(p); p[0] = PacketCrypto.MAGIC; p[1] = PacketCrypto.VERSION } }
            val start = System.nanoTime()
            DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
                var i = 0
                while (System.nanoTime() - start < 500 * ms) {
                    val p = junk[i++ and 15]
                    s.send(DatagramPacket(p, p.size, InetSocketAddress(V4, host.port)))
                }
            }
            Thread.sleep(100)
            val secs = (System.nanoTime() - start) / 1e9
            val decrypted = host.authFailures.get()
            assertTrue("decrypted $decrypted in %.2f s".format(secs), decrypted <= 200 + 100 * secs + 10)
            assertTrue("dropped unread ${host.limited.get()}", host.limited.get() > decrypted)
        } finally {
            host.close()
        }
    }

    /** Someone who has the invite (the key) but no pipe: what they send is limited the same way. */
    @Test
    fun authenticPacketsForConnectionsWeDontHaveAreLimitedToo() = UdpTestPair().use { t ->
        val (g, _) = t.connect()
        val crypto = PacketCrypto(t.key)
        val r = Random(3)
        fun sealed(type: Int, connId: Long, fromHost: Boolean = false): ByteArray {
            val b = ByteBuffer.allocate(64).put((type or if (fromHost) 0x80 else 0).toByte()).putLong(connId).putLong(1)
            if (type == UdpTransport.DATA) b.putInt(1).put(0)
            if (type == UdpTransport.HELLO) b.put(1)
            return crypto.seal(b.array(), b.position())
        }
        val forged = ArrayList<ByteArray>()
        repeat(500) { forged += sealed(UdpTransport.DATA, r.nextLong()) }
        repeat(500) { forged += sealed(UdpTransport.HELLO, r.nextLong()) } // the acceptor refuses these: each leaves a tomb
        repeat(300) { forged += sealed(listOf(UdpTransport.PROBE, UdpTransport.BYE, UdpTransport.WELCOME)[it % 3], r.nextLong()) }
        repeat(300) { forged += sealed(UdpTransport.HELLO, t.connId, fromHost = true) } // our own kind, reflected
        forged.shuffle(r)
        val start = System.nanoTime()
        DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
            for (p in forged) s.send(DatagramPacket(p, p.size, InetSocketAddress(V4, t.host.port)))
        }
        Thread.sleep(300)
        val secs = (System.nanoTime() - start) / 1e9
        val sizes = t.host.tableSizes()
        val let = forged.size - t.host.limited.get()
        println("forged flood: ${forged.size} authentic datagrams, $let let through in %.2f s; tables $sizes".format(secs))
        assertTrue("let through $let", let <= 100 + 50 * secs + 10)
        assertEquals(1, sizes["conns"])
        assertEquals(0, sizes["probes"])
        assertTrue("tombs ${sizes["tombs"]}", sizes["tombs"]!! <= let)
        assertTrue("acceptor asked ${t.accepted.size} times", t.accepted.size <= let + 1)
        // The real pipe goes on as if nothing happened.
        t.hostPeer.hook = { p, m -> p.send(m) }
        val rtt = roundTrips(t, g, 20, 2, 3)
        assertTrue("p90 ${rtt.pct(90)} ms", rtt.pct(90) < 25)
    }

    /** Nothing a stranger sends makes per-connection state: only an authentic HELLO does. */
    @Test
    fun noStateForStrangers() {
        val key = UdpTestKit.newKey()
        val host = UdpTransport(key, true, UdpTestKit.opts())
        val accepted = AtomicLong()
        host.setAcceptor { _, _ -> accepted.incrementAndGet(); null }
        try {
            val wrongKey = PacketCrypto(RoomKey(key.roomId, ByteArray(16) { 1 }))
            val r = Random(5)
            DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
                fun send(p: ByteArray) = s.send(DatagramPacket(p, p.size, InetSocketAddress(V4, host.port)))
                repeat(2000) {
                    val hello = ByteBuffer.allocate(18).put(UdpTransport.HELLO.toByte()).putLong(r.nextLong()).putLong(1).put(1)
                    send(wrongKey.seal(hello.array(), hello.position())) // a HELLO from someone without the invite
                    send(ByteArray(r.nextInt(1500)).also { r.nextBytes(it) }) // noise
                    send(ByteArray(UdpTransport.MAX_PACKET + 1 + r.nextInt(200)).also { it[0] = PacketCrypto.MAGIC; it[1] = PacketCrypto.VERSION }) // too big
                }
            }
            Thread.sleep(300)
            val sizes = host.tableSizes()
            assertEquals(0L, accepted.get())
            assertEquals("no pipes, probes or tombs: $sizes", listOf(0, 0, 0), listOf(sizes["conns"], sizes["probes"], sizes["tombs"]))
            assertEquals(1, sizes["sources"])
            assertTrue("decrypted ${host.authFailures.get()}", host.authFailures.get() <= 150)
            assertTrue(host.rejected.get() >= 4000)
        } finally {
            host.close()
        }
    }
}
