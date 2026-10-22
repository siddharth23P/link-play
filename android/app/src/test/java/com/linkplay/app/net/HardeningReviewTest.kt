package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import com.linkplay.app.net.UdpTestKit.cand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Review of the stranger-flood hardening (SourceLimiter and its use in UdpTransport).
 */
class HardeningReviewTest {
    private val ms = 1_000_000L

    /** The /64 number [i] of one attacker's /48 (a VPS or a home line delegated a /48 has 65 536 of them). */
    private fun slash64(i: Int): InetAddress = InetAddress.getByAddress(ByteArray(16).also { b ->
        b[0] = 0x2a; b[1] = 0x01; b[2] = 0x04; b[3] = 0xf8.toByte(); b[4] = 0x0c; b[5] = 0x17
        b[6] = (i shr 8).toByte(); b[7] = i.toByte()
        b[15] = 1
    })

    /**
     * A source that used up its tokens must not get a fresh burst of [burst] just because the table had
     * to make room for others. Today the least recently heard-from bucket is simply dropped, and a source
     * without a bucket is "never seen": allowed, and handed a full burst again.
     */
    @Test
    fun aFlooderForgottenByAFullTableDoesNotGetAFreshBurst() {
        val l = SourceLimiter(50.0, 100, 1024)
        val flooder = slash64(60_000)
        val t0 = 1_000_000_000L
        repeat(100) { l.charge(flooder, t0) }
        assertFalse("burst spent", l.allows(flooder, t0))
        // The same attacker sends one useless datagram from each of 1024 other /64s of its /48.
        for (i in 0 until 1024) l.charge(slash64(i), t0)
        assertFalse("the flooder must still be limited after the table filled up", l.allows(flooder, t0))
    }

    /**
     * A flood rotating over more sources than the table holds: every source sends 200 useless datagrams
     * a second. Each may cost at most its burst plus its rate (100 + 50 in one second); today every single
     * datagram is let through to decryption, because each source has been forgotten by the time it comes
     * round again. (Measured: with every junk datagram decrypted, a real guest's round trip on loopback goes
     * from ~5 ms to ~770 ms p50 / ~1130 ms p90.)
     */
    @Test
    fun aFloodRotatingOverMoreSourcesThanTheTableHoldsIsHeldToEachSourcesAllowance() {
        val l = SourceLimiter(50.0, 100, 1024)
        val sources = List(2048) { slash64(it) }
        val t0 = 1_000_000_000L
        var sent = 0L
        var allowed = 0L
        for (round in 0 until 200) { // 1 s
            val now = t0 + round * 5 * ms
            for (s in sources) {
                sent++
                if (l.allows(s, now)) {
                    allowed++
                    l.charge(s, now) // it was junk: decrypted for nothing
                }
            }
        }
        val allowance = sources.size * (100 + 50)
        assertTrue("$allowed of $sent junk datagrams decrypted; at most $allowance should be", allowed <= allowance)
    }

    /**
     * A guest that is already in the room changes port (NAT rebinding, or a CGNAT co-tenant / the same /64 is
     * flooding the host). Its packets now come from an address the host doesn't "know", so they are dropped
     * unread while the shared IPv4 / IPv6 /64 bucket is empty: the pipe stalls and then times out, although
     * the report says guests already connected are exempt.
     */
    @Test
    fun aConnectedGuestWhosePortChangesDuringAFloodFromItsAddressKeepsItsPipe() {
        val fast = UdpTestKit.opts().copy(deadTimeoutMs = 4000)
        UdpTestPair(hostOptions = fast, guestOptions = fast).use { t ->
            UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { nat ->
                val (g, h) = t.connect(listOf(cand(V4, nat.address.port)))
                g.send("before".toByteArray())
                assertEquals("before", t.hostPeer.nextText())

                val stop = AtomicBoolean()
                val sent = AtomicLong()
                val flood = Thread {
                    DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
                        val r = Random(9)
                        val junk = List(16) {
                            ByteArray(UdpTransport.MAX_PACKET).also { p -> r.nextBytes(p); p[0] = PacketCrypto.MAGIC; p[1] = PacketCrypto.VERSION }
                        }
                        val to = InetSocketAddress(V4, t.host.port)
                        var i = 0
                        while (!stop.get()) {
                            val p = junk[i++ and 15]
                            s.send(DatagramPacket(p, p.size, to))
                            sent.incrementAndGet()
                        }
                    }
                }
                flood.start()
                try {
                    Thread.sleep(300) // the shared 127.0.0.1 bucket is empty by now
                    val oldPort = nat.upstreamPort
                    nat.rebind()
                    assertTrue(nat.upstreamPort != oldPort)
                    g.send("after".toByteArray())
                    val got = t.hostPeer.messages.poll(3000, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { String(it) }
                    println("rebinding during a flood: ${sent.get()} junk sent, ${t.host.limited.get()} dropped unread; host got ${got ?: "nothing"}, path ${h.path}")
                    assertEquals("the moved guest's message reaches the host while the flood goes on", "after", got)
                    assertFalse("the pipe is still up", t.hostPeer.closed.isDone || t.guestPeer.closed.isDone)
                } finally {
                    stop.set(true)
                    flood.join()
                }
            }
        }
    }
}
