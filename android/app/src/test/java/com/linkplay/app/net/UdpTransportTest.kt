package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import com.linkplay.app.net.UdpTestKit.V6
import com.linkplay.app.net.UdpTestKit.cand
import com.linkplay.app.net.UdpTestKit.fromHost
import com.linkplay.app.net.UdpTestKit.newKey
import com.linkplay.app.net.UdpTestKit.opts
import com.linkplay.app.net.UdpTestKit.typeOf
import com.linkplay.app.net.UdpTestKit.waitUntil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UdpTransportTest {
    private fun Pipe.sendText(s: String) = send(s.toByteArray())

    /** [n] numbered messages each way arrive in order. */
    private fun exchange(t: UdpTestPair, g: Pipe, h: Pipe, n: Int = 300) {
        for (i in 0 until n) {
            g.send(ByteBuffer.allocate(4 + i % 50).putInt(i).array())
            h.send(ByteBuffer.allocate(4 + i % 70).putInt(-i).array())
        }
        for (i in 0 until n) {
            assertEquals(i, ByteBuffer.wrap(t.hostPeer.next()).int)
            assertEquals(-i, ByteBuffer.wrap(t.guestPeer.next()).int)
        }
    }

    @Test
    fun loopbackIpv4() {
        val largest = AtomicInteger()
        val track = opts().copy(outboundFilter = { p, _ -> largest.accumulateAndGet(p.size) { a, b -> maxOf(a, b) }; true })
        UdpTestPair(hostOptions = track, guestOptions = track).use { t -> loopbackIpv4(t, largest) }
    }

    private fun loopbackIpv4(t: UdpTestPair, largest: AtomicInteger) {
        val (g, h) = t.connect()
        assertEquals("127.0.0.1:${t.host.port} LAN", g.path)
        assertEquals("127.0.0.1:${t.guest.port} LAN", h.path)
        g.sendText("hi host")
        assertEquals("hi host", t.hostPeer.nextText())
        h.sendText("hi guest")
        assertEquals("hi guest", t.guestPeer.nextText())
        g.send(ByteArray(Pipe.MAX_MESSAGE) { it.toByte() })
        assertArrayEquals(ByteArray(Pipe.MAX_MESSAGE) { it.toByte() }, t.hostPeer.next())
        g.send(ByteArray(0))
        assertEquals(0, t.hostPeer.next().size)
        exchange(t, g, h)
        repeat(30) { g.send(ByteArray(Pipe.MAX_MESSAGE - it)) }
        repeat(30) { assertEquals(Pipe.MAX_MESSAGE - it, t.hostPeer.next().size) }
        assertTrue("largest datagram ${largest.get()}", largest.get() in 1100..UdpTransport.MAX_PACKET)
        try {
            g.send(ByteArray(Pipe.MAX_MESSAGE + 1))
            throw AssertionError("an oversized message was taken")
        } catch (e: IllegalArgumentException) {
        }
        assertEquals(listOf(t.connId), t.accepted.toList())
    }

    @Test
    fun loopbackIpv6() = UdpTestPair(hostOptions = opts(V6), guestOptions = opts(V6)).use { t ->
        val (g, h) = t.connect(listOf(cand(V6, t.host.port, Candidate.Kind.IPV6)))
        assertEquals("[::1]:${t.host.port} IPv6 direct", g.path)
        assertTrue(h.path, h.path.startsWith("[::1]:${t.guest.port} "))
        exchange(t, g, h)
    }

    /** The production socket: IPv6 wildcard taking IPv4 too. Opt-in: on a Mac it can make the firewall ask. */
    @Test
    fun dualStackWildcardSocket() {
        assumeTrue("set LINKPLAY_WILDCARD_TESTS=1", System.getenv("LINKPLAY_WILDCARD_TESTS") == "1")
        val key = newKey()
        val host = UdpTransport(key, true, opts().copy(bindAddress = null))
        val peers = ConcurrentHashMap<Long, UdpTestPeer>()
        host.setAcceptor { id, _ -> UdpTestPeer().also { peers[id] = it } }
        try {
            for ((id, ip) in listOf(1L to V4, 2L to V6)) {
                val guest = UdpTransport(key, false, opts(ip))
                val open = CompletableFuture<Pipe>()
                guest.connect(id, listOf(cand(ip, host.port, Candidate.Kind.IPV6)), UdpTestPeer()) { open.complete(it) }
                val pipe = open.get(3, java.util.concurrent.TimeUnit.SECONDS)
                pipe.sendText("via $ip")
                waitUntil(2000, "message via $ip") { peers[id]?.messages?.isNotEmpty() == true }
                assertEquals("via $ip", peers[id]!!.nextText())
                guest.close()
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun guestPrefersLanWhenItAnswersInTime() {
        UdpTestPair().use { t ->
            UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { fast ->
                UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { slow ->
                    slow.delayMs = 80 // its WELCOME comes ~160 ms after the fast one: inside the 300 ms window
                    val (g, h) = t.connect(listOf(cand(V4, fast.address.port, Candidate.Kind.MAPPED), cand(V4, slow.address.port, Candidate.Kind.LAN)))
                    assertEquals("127.0.0.1:${slow.address.port} LAN", g.path)
                    g.sendText("over lan")
                    assertEquals("over lan", t.hostPeer.nextText())
                    waitUntil(1000, "host follows the guest's choice") { h.path.startsWith("127.0.0.1:${slow.upstreamPort} ") }
                }
            }
        }
        UdpTestPair().use { t ->
            UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { fast ->
                UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { slow ->
                    slow.delayMs = 250 // ~500 ms round trip: too late, the first answer wins
                    val (g, _) = t.connect(listOf(cand(V4, fast.address.port, Candidate.Kind.MAPPED), cand(V4, slow.address.port, Candidate.Kind.LAN)))
                    assertEquals("127.0.0.1:${fast.address.port} public IPv4", g.path)
                    g.sendText("over the mapped port")
                    assertEquals("over the mapped port", t.hostPeer.nextText())
                }
            }
        }
    }

    /**
     * Both sides behind stateful firewalls that only let in packets from addresses they sent to. The guest's
     * HELLOs open its own firewall but die at the host's, until the host probes the guest's reply-code address.
     */
    @Test
    fun firewalledHostConnectsAfterProbe() {
        fun firewall(base: TransportOptions): TransportOptions {
            val sentTo = ConcurrentHashMap.newKeySet<InetSocketAddress>()
            return base.copy(outboundFilter = { _, to -> sentTo.add(to); true }, inboundFilter = { _, from -> from in sentTo })
        }
        UdpTestPair(hostOptions = firewall(opts()), guestOptions = firewall(opts())).use { t ->
            t.start()
            Thread.sleep(1000)
            assertFalse("guest got through a closed firewall", t.guestPipe.isDone)
            assertTrue(t.accepted.isEmpty())
            t.host.probe(t.connId, listOf(cand(V4, t.guest.port)))
            val (g, h) = t.awaitOpen(2000)
            exchange(t, g, h, 50)
        }
    }

    @Test
    fun pathMovesWhenTheGuestsPortChanges() = UdpTestPair().use { t ->
        UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { nat ->
            val (g, h) = t.connect(listOf(cand(V4, nat.address.port)))
            g.sendText("before")
            assertEquals("before", t.hostPeer.nextText())
            val oldPort = nat.upstreamPort
            assertTrue(h.path, h.path.startsWith("127.0.0.1:$oldPort "))
            nat.rebind()
            assertTrue(nat.upstreamPort != oldPort)
            g.sendText("after")
            assertEquals("after", t.hostPeer.nextText())
            assertTrue(h.path, h.path.startsWith("127.0.0.1:${nat.upstreamPort} "))
            h.sendText("back")
            assertEquals("back", t.guestPeer.nextText())
            exchange(t, g, h, 50)
        }
    }

    @Test
    fun foreignAndBrokenPacketsAreIgnored() = UdpTestPair().use { t ->
        val (g, h) = t.connect()
        exchange(t, g, h, 5)
        val path = h.path

        // Right room id, wrong secret: never accepted, nothing opens.
        val intruder = UdpTransport(RoomKey(t.key.roomId, ByteArray(16) { 7 }), false, opts())
        val intruderOpen = AtomicBoolean()
        intruder.connect(99, listOf(cand(V4, t.host.port)), UdpTestPeer()) { intruderOpen.set(true) }

        // A packet the host WOULD accept (the guest's message after the next 20), sent truncated or with a bit flipped.
        val forged = ByteBuffer.allocate(100).put(UdpTransport.DATA.toByte()).putLong(t.connId).putLong(Long.MAX_VALUE / 2)
            .putInt(1).put(1).putInt(5 + 20 + 1).putShort(6).put("forged".toByteArray())
        val packet = PacketCrypto(t.key).seal(forged.array(), forged.position())
        val before = t.host.rejected.get()
        var junk = 0
        DatagramSocket(InetSocketAddress(V4, 0)).use { s ->
            fun send(p: ByteArray) {
                s.send(DatagramPacket(p, p.size, InetSocketAddress(V4, t.host.port)))
                junk++
            }
            for (len in 0 until packet.size) send(packet.copyOf(len))
            for (bit in 0 until packet.size * 8) send(packet.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl bit % 8)).toByte() })
            repeat(50) { send(ByteArray(2 + UdpTestKit.random.nextInt(1199)).also { UdpTestKit.random.nextBytes(it); it[0] = 'L'.code.toByte(); it[1] = 1 }) }
            Thread.sleep(800)
            assertFalse(intruderOpen.get())
            assertEquals(listOf(t.connId), t.accepted.toList())
            // Every junk datagram but the empty one (0 bytes) counted; the intruder's HELLOs too.
            assertTrue("rejected ${t.host.rejected.get() - before} of $junk", t.host.rejected.get() - before >= junk - 1 + 3)
            assertNull(t.hostPeer.messages.poll(100, MILLISECONDS))
            assertEquals(path, h.path)
            exchange(t, g, h, 20)

            // Control: the intact packet is authentic, so the rejections above were down to the damage.
            send(packet)
            assertEquals("forged", t.hostPeer.nextText())
        }
        intruder.close()
    }

    @Test
    fun replayedPacketsDoNotMoveThePathOrRepeatMessages() {
        val sent = ConcurrentLinkedQueue<ByteArray>()
        UdpTestPair(guestOptions = opts().copy(outboundFilter = { p, _ -> sent.add(p); true })).use { t ->
            val (g, h) = t.connect()
            g.sendText("once")
            assertEquals("once", t.hostPeer.nextText())
            val crypto = PacketCrypto(t.key)
            val hello = sent.first { typeOf(crypto, it) == UdpTransport.HELLO }
            val data = sent.filter { typeOf(crypto, it) == UdpTransport.DATA }.maxByOrNull { it.size }!!
            val path = h.path
            DatagramSocket(InetSocketAddress(V4, 0)).use { attacker ->
                for (p in listOf(data, hello, data)) attacker.send(DatagramPacket(p, p.size, InetSocketAddress(V4, t.host.port)))
                Thread.sleep(300)
                assertEquals(path, h.path)
                assertNull(t.hostPeer.messages.poll(100, MILLISECONDS))
                g.sendText("still here")
                assertEquals("still here", t.hostPeer.nextText())
                h.sendText("and back")
                assertEquals("and back", t.guestPeer.nextText())
                assertEquals(path, h.path)
            }
        }
    }

    @Test
    fun silentPeerTimesOutAndReplaysAreNoSignOfLife() {
        val down = AtomicBoolean()
        val sent = ConcurrentLinkedQueue<ByteArray>()
        val fast = opts().copy(deadTimeoutMs = 800, keepaliveMs = 100)
        val guestOpts = fast.copy(outboundFilter = { p, _ -> sent.add(p); !down.get() }, inboundFilter = { _, _ -> !down.get() })
        UdpTestPair(hostOptions = fast, guestOptions = guestOpts).use { t ->
            t.connect()
            Thread.sleep(1500) // idle, but keepalives hold it open past the dead timeout
            assertFalse(t.hostPeer.closed.isDone)
            assertFalse(t.guestPeer.closed.isDone)
            down.set(true) // the guest's phone vanishes...
            val replayer = Thread {
                DatagramSocket(InetSocketAddress(V4, 0)).use { s -> // ...while someone replays what it sent
                    for (p in sent.toList().takeLast(40)) {
                        if (t.hostPeer.closed.isDone) break
                        s.send(DatagramPacket(p, p.size, InetSocketAddress(V4, t.host.port)))
                        Thread.sleep(30)
                    }
                }
            }.apply { start() }
            val start = System.nanoTime()
            assertEquals("timeout", t.hostPeer.closedReason(3000))
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("timed out after $ms ms", ms in 600..1600)
            assertEquals("timeout", t.guestPeer.closedReason(3000))
            replayer.join()
        }
    }

    @Test
    fun guestGivesUpWhenNobodyAnswers() = DatagramSocket(InetSocketAddress(V4, 0)).use { silent ->
        val heard = AtomicInteger()
        val listener = Thread {
            val p = DatagramPacket(ByteArray(2048), 2048)
            try {
                while (true) { silent.receive(p); heard.incrementAndGet() }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true; start() }
        val guest = UdpTransport(newKey(), false, opts().copy(connectTimeoutMs = 700))
        val peer = UdpTestPeer()
        val opened = AtomicBoolean()
        guest.connect(5, listOf(cand(V4, silent.localPort)), peer) { opened.set(true) }
        assertEquals("could not reach the host", peer.closedReason(3000))
        assertFalse(opened.get())
        assertTrue("HELLOs every 200 ms, got ${heard.get()}", heard.get() in 3..8) // 4 HELLOs + 2 BYEs
        guest.close()
        silent.close()
        listener.join(1000)
    }

    @Test
    fun refusedGuestHearsWhy() {
        val key = newKey()
        val host = UdpTransport(key, true, opts())
        host.setAcceptor { _, _ -> null }
        val guest = UdpTransport(key, false, opts())
        val peer = UdpTestPeer()
        val opened = AtomicBoolean()
        guest.connect(7, listOf(cand(V4, host.port)), peer) { opened.set(true) }
        assertEquals("the host refused the connection", peer.closedReason(2000))
        assertFalse(opened.get())
        guest.close()
        host.close()
    }

    @Test
    fun pipeCloseDeliversTheLastMessagesThenTheReason() = UdpTestPair().use { t ->
        val (g, _) = t.connect()
        g.sendText("a")
        g.sendText("b")
        g.close("see you")
        g.sendText("too late")
        assertEquals("a", t.hostPeer.nextText())
        assertEquals("b", t.hostPeer.nextText())
        assertEquals("see you", t.hostPeer.closedReason())
        assertNull(t.hostPeer.messages.poll(100, MILLISECONDS))
        Thread.sleep(200)
        assertFalse("closing is reported to the other side only", t.guestPeer.closed.isDone)
    }

    @Test
    fun bothSidesClosingAtOnceReportNothingLocally() = UdpTestPair().use { t ->
        val (g, h) = t.connect()
        g.close("guest leaves")
        h.close("host leaves")
        Thread.sleep(500)
        assertFalse(t.guestPeer.closed.isDone)
        assertFalse(t.hostPeer.closed.isDone)
    }

    @Test
    fun pipeCloseWaitsForLostMessages() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val armed = AtomicBoolean()
        val drop = AtomicInteger(3) // once armed, the guest's next three DATA packets get lost
        val guestOpts = opts().copy(outboundFilter = { p, _ ->
            !(armed.get() && typeOf(crypto, p) == UdpTransport.DATA && drop.getAndDecrement() > 0)
        })
        UdpTestPair(key, guestOptions = guestOpts).use { t ->
            val (g, _) = t.connect()
            Thread.sleep(50)
            armed.set(true)
            g.sendText("important")
            g.close("done")
            assertEquals("important", t.hostPeer.nextText())
            assertEquals("done", t.hostPeer.closedReason())
            assertTrue("the drops happened", drop.get() <= 0)
        }
    }

    @Test
    fun closingTheHostTransportTellsTheGuest() = UdpTestPair().use { t ->
        val (_, h) = t.connect()
        h.sendText("last words")
        t.host.close()
        assertEquals("last words", t.guestPeer.nextText())
        assertEquals("the host closed the room", t.guestPeer.closedReason())
        assertFalse(t.hostPeer.closed.isDone)
    }

    @Test
    fun closingTheGuestTransportTellsTheHost() = UdpTestPair().use { t ->
        t.connect()
        t.guest.close()
        assertEquals("the guest left", t.hostPeer.closedReason())
    }

    @Test
    fun backlogOverflowClosesBothEnds() {
        val mute = AtomicBoolean()
        UdpTestPair(hostOptions = opts().copy(maxBacklog = 50), guestOptions = opts().copy(outboundFilter = { _, _ -> !mute.get() })).use { t ->
            val (_, h) = t.connect()
            Thread.sleep(50)
            mute.set(true) // the guest hears everything, but its acks never arrive
            repeat(60) {
                h.send(byteArrayOf(it.toByte()))
                Thread.sleep(2)
            }
            assertEquals("too far behind", t.hostPeer.closedReason())
            assertEquals("too far behind", t.guestPeer.closedReason())
        }
    }

    @Test
    fun hostServesSeveralGuests() {
        val key = newKey()
        val host = UdpTransport(key, true, opts())
        val atHost = ConcurrentHashMap<Long, UdpTestPeer>()
        host.setAcceptor { id, pipe -> UdpTestPeer().also { p -> p.hook = { _, m -> pipe.send(m + id.toByte()) }; atHost[id] = p } }
        val guests = (1L..3L).map { id ->
            val g = UdpTransport(key, false, opts())
            val peer = UdpTestPeer()
            val open = CompletableFuture<Pipe>()
            g.connect(id, listOf(cand(V4, host.port)), peer) { open.complete(it) }
            Triple(g, peer, open)
        }
        for ((i, x) in guests.withIndex()) {
            val pipe = x.third.get(3, java.util.concurrent.TimeUnit.SECONDS)
            pipe.send(byteArrayOf(10, 20))
            assertArrayEquals(byteArrayOf(10, 20, (i + 1).toByte()), x.second.next()) // echoed by its own pipe
        }
        guests.forEach { it.first.close() }
        host.close()
    }

    @Test
    fun loopbackLatencyIsLow() = UdpTestPair().use { t ->
        val (g, h) = t.connect()
        val samples = ArrayList<Long>()
        repeat(200) { i ->
            val from = if (i % 2 == 0) h else g
            val to = if (i % 2 == 0) t.guestPeer else t.hostPeer
            val start = System.nanoTime()
            from.send(byteArrayOf(i.toByte()))
            to.next()
            samples.add(System.nanoTime() - start)
            Thread.sleep(3)
        }
        samples.sort()
        val median = samples[samples.size / 2] / 1e6
        val p90 = samples[samples.size * 9 / 10] / 1e6
        println("loopback one-way latency: median %.2f ms, p90 %.2f ms, max %.2f ms".format(median, p90, samples.last() / 1e6))
        assertTrue("median $median ms", median < 10)
        assertTrue("p90 $p90 ms", p90 < 20)
    }

    @Test
    fun replayWindowCountsEachCounterOnce() {
        val w = ReplayWindow()
        assertTrue(w.firstSight(1))
        assertFalse(w.firstSight(1))
        assertTrue(w.firstSight(5))
        assertTrue("late but new", w.firstSight(3))
        for (c in listOf(3L, 5L, 1L, 0L)) assertFalse("$c again", w.firstSight(c))
        assertTrue(w.firstSight(2))
        assertTrue(w.firstSight(4))
        assertEquals(5, w.highest)
        assertTrue(w.firstSight(68)) // 5 is now 63 below: still remembered, 4 and older are out of the window
        assertFalse(w.firstSight(5))
        assertTrue(w.firstSight(6))
        assertFalse("older than the window", w.firstSight(4))
        assertTrue(w.firstSight(1000)) // a jump forgets everything below
        assertTrue(w.firstSight(999))
        assertFalse(w.firstSight(936))
        assertTrue(w.firstSight(937))
        assertFalse(w.firstSight(Long.MIN_VALUE))
        assertEquals(1000, w.highest)
    }

    /** Both directions share the key, so each packet says who sent it, and a side refuses its own packets sent back. */
    @Test
    fun packetsSayWhichSideSentThem() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val byHost = ConcurrentLinkedQueue<ByteArray>()
        val byGuest = ConcurrentLinkedQueue<ByteArray>()
        UdpTestPair(
            key,
            hostOptions = opts().copy(outboundFilter = { p, _ -> byHost.add(p); true }),
            guestOptions = opts().copy(outboundFilter = { p, _ -> byGuest.add(p); true }),
        ).use { t ->
            val (g, h) = t.connect()
            g.sendText("up")
            assertEquals("up", t.hostPeer.nextText())
            h.sendText("down")
            assertEquals("down", t.guestPeer.nextText())
            assertTrue(byHost.all { fromHost(crypto, it) == true } && byHost.any { typeOf(crypto, it) == UdpTransport.WELCOME })
            assertTrue(byGuest.all { fromHost(crypto, it) == false } && byGuest.any { typeOf(crypto, it) == UdpTransport.HELLO })

            // The guest's latest DATA (carrying "up") sent back to the guest: refused, nothing delivered.
            val own = byGuest.filter { typeOf(crypto, it) == UdpTransport.DATA }.maxByOrNull { it.size }!!
            val before = t.guest.rejected.get()
            DatagramSocket(InetSocketAddress(V4, 0)).use { s -> s.send(DatagramPacket(own, own.size, InetSocketAddress(V4, t.guest.port))) }
            waitUntil(1000, "the reflection to be refused") { t.guest.rejected.get() > before }
            assertNull(t.guestPeer.messages.poll(100, MILLISECONDS))
            assertEquals("127.0.0.1:${t.host.port} LAN", g.path)
        }
    }

    /**
     * The guest's HELLOs go out in candidate order with rising counters, so a HELLO on a slower path reaches
     * the host after a later one: it is late, not a replay, and must still be answered, or the guest could
     * never pick that path (here the LAN one) whenever it is listed before a faster one.
     */
    @Test
    fun aHelloOvertakenOnAFasterPathIsStillAnswered() = UdpTestPair().use { t ->
        UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { slow ->
            UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { fast ->
                slow.delayMs = 60
                val (g, h) = t.connect(listOf(cand(V4, slow.address.port, Candidate.Kind.LAN), cand(V4, fast.address.port, Candidate.Kind.MAPPED)))
                assertEquals("127.0.0.1:${slow.address.port} LAN", g.path)
                g.sendText("over lan")
                assertEquals("over lan", t.hostPeer.nextText())
                waitUntil(1000, "host follows the guest's choice") { h.path.startsWith("127.0.0.1:${slow.upstreamPort} ") }
            }
        }
    }

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

    /** Answering a replay would make every captured HELLO or PROBE a lever for floods and for path choice. */
    @Test
    fun replayedHelloAndProbeGetNoAnswer() {
        val key = newKey()
        val crypto = PacketCrypto(key)
        val byGuest = ConcurrentLinkedQueue<ByteArray>()
        UdpTestPair(key, guestOptions = opts().copy(outboundFilter = { p, _ -> byGuest.add(p); true })).use { t ->
            t.connect()
            val hello = byGuest.first { typeOf(crypto, it) == UdpTransport.HELLO }
            DatagramSocket(InetSocketAddress(V4, 0)).use { attacker ->
                repeat(5) { attacker.send(DatagramPacket(hello, hello.size, InetSocketAddress(V4, t.host.port))) }
                assertEquals("WELCOMEs to the replayer", 0, attacker.drain(400))
            }
        }

        // A guest still connecting (the host takes nobody yet) while the host probes it.
        val probes = ConcurrentLinkedQueue<ByteArray>()
        val host = UdpTransport(key, true, opts().copy(outboundFilter = { p, _ -> if (typeOf(crypto, p) == UdpTransport.PROBE) probes.add(p); true }))
        val guest = UdpTransport(key, false, opts())
        try {
            guest.connect(77, listOf(cand(V4, host.port)), UdpTestPeer()) {}
            host.probe(77, listOf(cand(V4, guest.port)))
            waitUntil(2000, "three rounds of PROBEs") { probes.size >= 3 }
            DatagramSocket(InetSocketAddress(V4, 0)).use { attacker ->
                val probe = probes.elementAt(1) // one the guest has surely seen (its pipe existed by then)
                repeat(5) { attacker.send(DatagramPacket(probe, probe.size, InetSocketAddress(V4, guest.port))) }
                assertEquals("HELLOs to the replayer", 0, attacker.drain(500))
            }
        } finally {
            guest.close()
            host.close()
        }
    }

    /** A binding success whose last attribute header ends exactly at byte 2048, the receive buffer's end. */
    private fun stunLookalike(): ByteArray {
        val b = ByteBuffer.allocate(2100)
        b.putShort(0x0101).putShort((2048 - 20).toShort()).putInt(Stun.MAGIC_COOKIE).put(ByteArray(12))
        b.putShort(0x8000.toShort()).putShort((2044 - 24).toShort()).put(ByteArray(2044 - 24))
        b.putShort(0x0020).putShort(0)
        return b.array()
    }

    @Test
    fun noDatagramStopsTheReceiver() {
        val uncaught = ConcurrentLinkedQueue<String>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e -> uncaught.add("${th.name}: $e") }
        val poison = byteArrayOf(1, 2, 3)
        try {
            // A reader that throws on one datagram stands in for any bug in reading untrusted bytes.
            val hostOpts = opts().copy(inboundFilter = { p, _ -> check(!p.contentEquals(poison)) { "a reader bug" }; true })
            UdpTestPair(hostOptions = hostOpts).use { t ->
                val (g, h) = t.connect()
                DatagramSocket(InetSocketAddress(V4, 0)).use { stunServer ->
                    // While a lookup waits, STUN-looking datagrams are parsed: send one built to overrun the buffer.
                    val lookup = Thread { t.host.stun(InetSocketAddress(V4, stunServer.localPort), 800) }.apply { start() }
                    Thread.sleep(100)
                    val bomb = stunLookalike()
                    stunServer.send(DatagramPacket(bomb, bomb.size, InetSocketAddress(V4, t.host.port)))
                    stunServer.send(DatagramPacket(poison, poison.size, InetSocketAddress(V4, t.host.port)))
                    lookup.join()
                }
                g.sendText("still here")
                assertEquals("still here", t.hostPeer.nextText())
                h.sendText("and back")
                assertEquals("and back", t.guestPeer.nextText())
                assertTrue("uncaught: $uncaught", uncaught.isEmpty())
                assertEquals(1L, t.host.rejected.get())
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /** Transport.connect promises an outcome even for an attempt that cannot start. */
    @Test
    fun connectThatCannotStartSaysWhy() = DatagramSocket(InetSocketAddress(V4, 0)).use { silent ->
        val guest = UdpTransport(newKey(), false, opts())
        val first = UdpTestPeer()
        guest.connect(9, listOf(cand(V4, silent.localPort)), first) {}
        val again = UdpTestPeer()
        guest.connect(9, listOf(cand(V4, silent.localPort)), again) {}
        assertEquals("this connection attempt is already running", again.closedReason(1000))
        Thread.sleep(100)
        assertFalse("the first attempt goes on", first.closed.isDone)
        guest.close()
        val late = UdpTestPeer()
        guest.connect(10, listOf(cand(V4, silent.localPort)), late) {}
        assertTrue("reported at once", late.closed.isDone)
        assertEquals("the link was closed", late.closedReason(0))
        assertFalse("close() reports nothing locally", first.closed.isDone)
    }

    @Test
    fun describesPaths() {
        fun d(ip: String, port: Int) = UdpTransport.format(InetSocketAddress(java.net.InetAddress.getByName(ip), port))
        assertEquals("[2401:4900::1]:50123", d("2401:4900:0:0:0:0:0:1", 50123))
        assertEquals("[::1]:1", d("::1", 1))
        assertEquals("[2001:db8::1:0:0:1]:2", d("2001:db8:0:0:1:0:0:1", 2)) // first of two equal runs
        assertEquals("[2001:db8:0:1:1:1:1:1]:3", d("2001:db8:0:1:1:1:1:1", 3)) // a single zero group stays
        assertEquals("192.168.1.5:4", d("192.168.1.5", 4))
        assertEquals(Candidate.Kind.LAN, UdpTransport.inferKind(java.net.InetAddress.getByName("10.0.0.2")))
        assertEquals(Candidate.Kind.LAN, UdpTransport.inferKind(java.net.InetAddress.getByName("fd00::5")))
        assertEquals(Candidate.Kind.IPV6, UdpTransport.inferKind(java.net.InetAddress.getByName("2401:4900::1")))
        assertEquals(Candidate.Kind.MAPPED, UdpTransport.inferKind(java.net.InetAddress.getByName("49.36.1.2")))
    }
}
