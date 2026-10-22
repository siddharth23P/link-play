package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import com.linkplay.app.net.UdpTestKit.cand
import com.linkplay.app.net.UdpTestKit.newKey
import com.linkplay.app.net.UdpTestKit.opts
import com.linkplay.app.net.UdpTestKit.typeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

/**
 * How long and how often a guest and a host keep trying while a reply code travels over a chat app,
 * what they report about how a connection got through, and the two-server NAT self-test.
 */
class WaitAndNatTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun sa(s: String, port: Int) = InetSocketAddress(ip(s), port)

    // ---------------------------------------------------------------- waiting for the reply code

    @Test
    fun defaultsWaitFifteenMinutesFastForTheFirstMinute() {
        val o = TransportOptions()
        assertEquals(15 * 60_000L, o.connectTimeoutMs)
        assertEquals(15 * 60_000L, o.probeDurationMs)
        assertEquals(200L, o.helloIntervalMs)
        assertEquals(200L, o.probeIntervalMs)
        assertEquals(60_000L, o.fastPhaseMs)
        assertEquals(1000L, o.slowIntervalMs)
    }

    /** Milliseconds since [start] at which packets of [type] went out, recorded by an outbound filter. */
    private class Sends(val crypto: PacketCrypto, val type: Int) {
        val start = System.nanoTime()
        val at = ConcurrentLinkedQueue<Double>()
        val filter: (ByteArray, InetSocketAddress) -> Boolean = { p, _ ->
            if (typeOf(crypto, p) == type) at.add((System.nanoTime() - start) / 1e6)
            true
        }
        fun count(from: Double, to: Double) = at.count { it >= from && it < to }
    }

    @Test
    fun helloSlowsDownAfterTheFastPhase() = DatagramSocket(InetSocketAddress(V4, 0)).use { silent ->
        val key = newKey()
        val hellos = Sends(PacketCrypto(key), UdpTransport.HELLO)
        val guest = UdpTransport(key, false, opts().copy(helloIntervalMs = 20, fastPhaseMs = 400, slowIntervalMs = 200,
            outboundFilter = hellos.filter))
        val peer = UdpTestPeer()
        guest.connect(1, listOf(cand(V4, silent.localPort)), peer) {}
        Thread.sleep(1500)
        guest.close()
        val fast = hellos.count(0.0, 380.0)
        val slow = hellos.count(500.0, 1500.0)
        assertTrue("fast phase: $fast HELLOs in 380 ms (${hellos.at})", fast in 12..20)
        assertTrue("slow phase: $slow HELLOs in 1000 ms (${hellos.at})", slow in 4..6)
        assertFalse("still trying", peer.closed.isDone)
    }

    @Test
    fun probesSlowDownThenStop() = DatagramSocket(InetSocketAddress(V4, 0)).use { silent ->
        val key = newKey()
        val probes = Sends(PacketCrypto(key), UdpTransport.PROBE)
        val host = UdpTransport(key, true, opts().copy(probeIntervalMs = 20, fastPhaseMs = 400, slowIntervalMs = 200,
            probeDurationMs = 1100, outboundFilter = probes.filter))
        host.probe(7, listOf(cand(V4, silent.localPort)))
        Thread.sleep(1700)
        host.close()
        val fast = probes.count(0.0, 380.0)
        val slow = probes.count(500.0, 1100.0)
        val after = probes.count(1200.0, 2000.0)
        assertTrue("fast phase: $fast PROBEs in 380 ms (${probes.at})", fast in 12..20)
        assertTrue("slow phase: $slow PROBEs in 600 ms (${probes.at})", slow in 2..4)
        assertEquals("stopped after probeDurationMs (${probes.at})", 0, after)
    }

    /** The host has the guest's reply code now: a guest in its slow phase goes back to the fast rate. */
    @Test
    fun aProbeSpeedsTheGuestUpAgain() {
        val key = newKey()
        val hellos = Sends(PacketCrypto(key), UdpTransport.HELLO)
        val host = UdpTransport(key, true, opts().copy(probeIntervalMs = 10_000)) // one PROBE, and no acceptor: it never answers
        val guest = UdpTransport(key, false, opts().copy(helloIntervalMs = 20, fastPhaseMs = 200, slowIntervalMs = 400,
            outboundFilter = hellos.filter))
        try {
            guest.connect(5, listOf(cand(V4, host.port)), UdpTestPeer()) {}
            Thread.sleep(700)
            val slow = hellos.count(300.0, 700.0)
            val probedAt = (System.nanoTime() - hellos.start) / 1e6
            host.probe(5, listOf(cand(V4, guest.port)))
            Thread.sleep(250)
            val fastAgain = hellos.count(probedAt + 30, probedAt + 190)
            assertTrue("slow before the PROBE: $slow in 400 ms (${hellos.at})", slow in 1..2)
            assertTrue("fast after it: $fastAgain in 160 ms (${hellos.at})", fastAgain >= 5)
        } finally {
            guest.close()
            host.close()
        }
    }

    // ---------------------------------------------------------------- how a guest got in

    @Test
    fun bothSidesTellWhetherTheReplyCodeWasNeeded() {
        class Arrival(val connId: Long, val path: String, val afterProbe: Boolean)
        val arrivals = LinkedBlockingQueue<Arrival>()
        val connects = LinkedBlockingQueue<Pair<Long, Boolean>>()
        fun hostOpts() = opts().copy(onGuestArrived = { id, path, after -> arrivals.add(Arrival(id, path, after)) })
        val guestOpts = opts().copy(onConnected = { _, _, ms, probed -> connects.add(ms to probed) })

        // The invite alone: the host's network lets the guest's HELLO in.
        UdpTestPair(hostOptions = hostOpts(), guestOptions = guestOpts).use { t ->
            t.connect()
            val a = arrivals.poll(2, SECONDS)!!
            assertEquals(t.connId, a.connId)
            assertEquals("127.0.0.1:${t.guest.port} LAN", a.path)
            assertFalse(a.afterProbe)
            val (ms, probed) = connects.poll(2, SECONDS)!!
            assertFalse(probed)
            assertTrue("connected after $ms ms", ms in 0..1000)
        }

        // Behind a firewall that only lets in addresses the host has sent to: in only once the host
        // probes the addresses from the guest's reply code.
        val opened = ConcurrentHashMap.newKeySet<InetSocketAddress>()
        val firewalled = hostOpts().copy(outboundFilter = { _, to -> opened.add(to); true }, inboundFilter = { _, from -> from in opened })
        UdpTestPair(hostOptions = firewalled, guestOptions = guestOpts).use { t ->
            t.start()
            Thread.sleep(600)
            assertNull("blocked without the reply code", arrivals.poll(0, SECONDS))
            assertFalse(t.guestPipe.isDone)
            t.host.probe(t.connId, listOf(cand(V4, t.guest.port)))
            t.awaitOpen()
            val a = arrivals.poll(2, SECONDS)!!
            assertTrue("after the reply code", a.afterProbe)
            val (ms, probed) = connects.poll(2, SECONDS)!!
            assertTrue("the guest saw the host's PROBE", probed)
            assertTrue("connected after $ms ms", ms >= 550)
        }
    }

    // ---------------------------------------------------------------- NAT self-test

    @Test
    fun natKinds() {
        val local = listOf(sa("21.13.4.5", 4000))
        assertEquals(NatCheck.Kind.NO_ANSWER, NatCheck(local, listOf(null, null)).kind)
        assertEquals(NatCheck.Kind.OPEN, NatCheck(local, listOf(sa("21.13.4.5", 4000), sa("21.13.4.5", 4000))).kind)
        assertEquals(NatCheck.Kind.OPEN, NatCheck(local, listOf(null, sa("21.13.4.5", 4000))).kind)
        assertEquals(NatCheck.Kind.EASY, NatCheck(local, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5000))).kind)
        assertEquals(NatCheck.Kind.STRICT, NatCheck(local, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5001))).kind)
        assertEquals(NatCheck.Kind.STRICT, NatCheck(emptyList(), listOf(sa("49.36.1.2", 5000), sa("49.36.1.3", 5000))).kind)
        assertEquals(NatCheck.Kind.UNSURE, NatCheck(local, listOf(sa("49.36.1.2", 5000), null)).kind)
        // Port kept but the interface address isn't what the world sees: NAT, not "no NAT".
        assertEquals(NatCheck.Kind.EASY, NatCheck(local, listOf(sa("49.36.1.2", 4000), sa("49.36.1.2", 4000))).kind)
        assertEquals(sa("49.36.1.2", 5000), NatCheck(local, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5000))).mapped)
        assertNull(NatCheck(local, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5001))).mapped)
        assertTrue(NatCheck(local, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5001))).strict)
        assertNull(NatCheck(local, listOf(sa("49.36.1.2", 5000), null)).mapped)
    }

    @Test
    fun mobileDataNeverOffersAnUnconfirmedPublicLookingIpv4() {
        // Carriers number their internal networks out of squatted public blocks.
        for (squatted in listOf("21.13.4.5", "22.1.2.3", "25.9.9.9", "29.40.1.1", "30.7.0.2", "56.0.0.9")) {
            val iface = listOf(sa(squatted, 4000))
            assertEquals(squatted, emptyList<Candidate>(), NatCheck.ipv4Candidates(iface, cellular = true, nat = null))
            assertEquals(squatted, emptyList<Candidate>(), NatCheck.ipv4Candidates(iface, true, NatCheck(iface, listOf(null, null))))
            // STUN saw a carrier NAT, not our interface address: only what STUN saw goes in (it's an easy NAT).
            val easy = NatCheck(iface, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5000)))
            assertEquals(listOf(Candidate(sa("49.36.1.2", 5000), Candidate.Kind.MAPPED)), NatCheck.ipv4Candidates(iface, true, easy))
            val strict = NatCheck(iface, listOf(sa("49.36.1.2", 5000), sa("49.36.1.2", 5002)))
            assertEquals(emptyList<Candidate>(), NatCheck.ipv4Candidates(iface, true, strict))
        }
        // A phone that really has a public IPv4, confirmed by STUN.
        val iface = listOf(sa("49.36.200.1", 4000))
        val open = NatCheck(iface, listOf(sa("49.36.200.1", 4000), sa("49.36.200.1", 4000)))
        assertEquals(listOf(Candidate(sa("49.36.200.1", 4000), Candidate.Kind.MAPPED)), NatCheck.ipv4Candidates(iface, true, open))
    }

    @Test
    fun otherNetworksKeepTheirInterfaceIpv4AndOfferStunOnlyWhenEasy() {
        val iface = listOf(sa("203.0.113.40", 4000))
        val mine = Candidate(sa("203.0.113.40", 4000), Candidate.Kind.MAPPED)
        assertEquals(listOf(mine), NatCheck.ipv4Candidates(iface, cellular = false, nat = null))
        val strict = NatCheck(iface, listOf(sa("198.51.100.1", 5000), sa("198.51.100.1", 6000)))
        assertEquals(listOf(mine), NatCheck.ipv4Candidates(iface, false, strict))
        val unsure = NatCheck(emptyList(), listOf(null, sa("198.51.100.1", 5000)))
        assertEquals(emptyList<Candidate>(), NatCheck.ipv4Candidates(emptyList(), false, unsure))
        val easy = NatCheck(emptyList(), listOf(sa("198.51.100.1", 5000), sa("198.51.100.1", 5000)))
        assertEquals(listOf(Candidate(sa("198.51.100.1", 5000), Candidate.Kind.MAPPED)), NatCheck.ipv4Candidates(emptyList(), false, easy))
    }

    /** A STUN server on loopback that tells everyone they are [mapped]. */
    private class TinyStun(val mapped: InetSocketAddress) : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(V4, 0))
        val address = InetSocketAddress(V4, socket.localPort)
        private val thread = Thread {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    p.setData(buf)
                    socket.receive(p)
                    if (p.length < 20 || buf[0].toInt() != 0 || buf[1].toInt() != 1) continue
                    val ip = mapped.address.address
                    val answer = ByteBuffer.allocate(32).putShort(0x0101).putShort(12).putInt(Stun.MAGIC_COOKIE).put(buf, 8, 12)
                        .putShort(0x0020).putShort(8).put(0).put(1).putShort((mapped.port xor 0x2112).toShort())
                        .putInt(ByteBuffer.wrap(ip).int xor Stun.MAGIC_COOKIE).array()
                    socket.send(DatagramPacket(answer, answer.size, p.socketAddress))
                }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true; start() }

        override fun close() = socket.close()
    }

    /** Two lookups at once on the room's own socket, as PhoneLink runs them, classified. */
    private fun selfTest(a: InetSocketAddress, b: InetSocketAddress): NatCheck {
        val t = UdpTransport(newKey(), true, opts())
        try {
            TinyStun(a).use { one ->
                TinyStun(b).use { two ->
                    val answers = arrayOfNulls<InetSocketAddress>(2)
                    val threads = listOf(one, two).mapIndexed { i, s -> Thread { answers[i] = t.stun(s.address, 1500) }.apply { start() } }
                    threads.forEach { it.join() }
                    return NatCheck(emptyList(), answers.toList())
                }
            }
        } finally {
            t.close()
        }
    }

    @Test
    fun twoStunServersOnOneSocketTellEasyFromStrict() {
        val easy = selfTest(sa("198.51.100.7", 40000), sa("198.51.100.7", 40000))
        assertEquals(easy.describe(), NatCheck.Kind.EASY, easy.kind)
        assertEquals(sa("198.51.100.7", 40000), easy.mapped)
        val strict = selfTest(sa("198.51.100.7", 40000), sa("198.51.100.7", 40002))
        assertEquals(strict.describe(), NatCheck.Kind.STRICT, strict.kind)
        assertNull(strict.mapped)
    }
}
