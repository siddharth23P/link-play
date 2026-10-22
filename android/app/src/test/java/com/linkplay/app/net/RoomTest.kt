package com.linkplay.app.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.random.Random

/** HostRoom + GuestSession over in-memory pipes (with latency and jitter where it matters). */
class RoomTest {
    private val nets = ArrayList<MemNet>()
    private val rooms = ArrayList<HostRoom>()
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val invite = Codes.newInvite("Amy", "Amy's room", TEST_ROM, 4, emptyList())

    private class Guest(val session: GuestSession, val rec: LinkRecorder, val transport: MemNet.FakeTransport)

    @After fun cleanUp() {
        rooms.forEach { it.close() }
        nets.forEach { it.close() }
    }

    private fun net(latencyMs: Long = 0, jitterMs: Long = 0) = MemNet(latencyMs, jitterMs).also { nets.add(it) }

    private fun host(net: MemNet, max: Int = 4, rec: LinkRecorder = LinkRecorder("host")): Pair<HostRoom, LinkRecorder> {
        val room = HostRoom("Amy's room", "Amy", TEST_ROM, max, rec) { logs.add(it) }
        rooms.add(room)
        net.hostTransport().setAcceptor(room::acceptor)
        waitUntil("host's own onJoined") { rec.joins.isNotEmpty() }
        return room to rec
    }

    private fun guest(net: MemNet, name: String, connId: Long, rom: RomId = TEST_ROM, expectLinkId: Int? = null): Guest {
        val rec = LinkRecorder(name)
        val t = net.guestTransport()
        val s = GuestSession(invite, name, rom, t, connId, rec)
        s.start()
        if (expectLinkId != null) waitUntil("$name joined") { rec.linkId == expectLinkId }
        return Guest(s, rec, t)
    }

    private fun assertClean(net: MemNet, vararg recs: LinkRecorder) {
        assertTrue("oversize pipe messages: ${net.errors}", net.errors.isEmpty())
        assertTrue("exceptions on the transport thread: ${net.sched.errors}", net.sched.errors.isEmpty())
        assertFalse("a malformed link frame was delivered", recs.any { it.badFrame })
        assertFalse("callback failures: $logs", logs.any { it.contains("callback failed") })
    }

    private fun command(p: Int, n: Int) = intArrayOf(0x1000 * (p + 1) + n, p, n, 0, 0, 0, 0, 0)

    @Test fun fourPlayersSeeIdenticalOrderedStreams() {
        val net = net(latencyMs = 20, jitterMs = 15)
        val (room, hostRec) = host(net)
        assertEquals(0, hostRec.linkId)
        assertEquals(listOf(RoomPlayer(0, "Amy", true)), hostRec.players)
        val guests = (1..3).map { guest(net, "G$it", it.toLong(), expectLinkId = it) }
        val everyone = listOf(hostRec) + guests.map { it.rec }
        waitUntil("everyone sees 4 players") { everyone.all { it.players.size == 4 } }
        assertEquals(listOf("Amy", "G1", "G2", "G3"), hostRec.players.map { it.name })
        assertEquals((0..3).toList(), hostRec.players.map { it.linkId })
        for (g in guests) {
            assertEquals(hostRec.players, g.rec.players)
            assertEquals("Amy's room", g.rec.joins.last().first)
        }

        // Everyone talks at once from their own thread: a handshake, then 40 commands.
        val senders: List<(ByteArray) -> Unit> = listOf<(ByteArray) -> Unit>(room::sendLink) + guests.map { g -> { b: ByteArray -> g.session.sendLink(b) } }
        senders.mapIndexed { p, send ->
            thread {
                val rnd = Random(p)
                send(gbaHandshake(0x8000 + p))
                for (n in 1..40) {
                    send(gbaCommand(command(p, n)))
                    Thread.sleep(rnd.nextLong(0, 8))
                }
            }
        }.forEach { it.join() }

        waitUntil("all commands sequenced", 10_000) { (0..3).all { hostRec.commandsFrom(it).size == 40 } }
        waitUntil("guests caught up") { guests.all { it.rec.frames.size == hostRec.frames.size } }
        Thread.sleep(100) // nothing more may arrive
        val reference = hostRec.snapshot()
        for (g in guests) assertEquals("${g.rec.label}'s stream", reference, g.rec.snapshot())
        for (p in 0..3) assertEquals("player $p's commands", (1..40).map { 0x1000 * (p + 1) + it }, hostRec.commandsFrom(p))
        assertEquals((0..3).map { it to 0x8000 + it }.toSet(), hostRec.handshakes().toSet())
        assertEquals(4, hostRec.handshakes().size)
        // Entries are 64 bytes with the sequencer's player byte 0.
        assertTrue(reference.map { it.unhex() }.filter { it[0].toInt() == 2 }.all { it.size == 66 && it[1].toInt() == 0 })
        for (r in everyone) {
            assertNull(r.fatal)
            assertNull(r.lost)
        }
        assertClean(net, *everyone.toTypedArray())
    }

    @Test fun backedUpStatePacketsCoalesce() {
        val net = net()
        val (_, hostRec) = host(net)
        val g = guest(net, "G1", 1, expectLinkId = 1)
        // A burst much faster than 60 Hz: positions 0..99, then a flag change, then 101..199.
        for (pos in 0 until 200) {
            val flags = if (pos < 100) 7 else 8
            g.session.sendLink(gbaCommand(intArrayOf(Sequencer.STATE_CMD, flags, pos, pos, 0, 3, 0x0100, 9)))
        }
        fun states() = hostRec.snapshot().map { it.unhex() }
            .filter { it[0].toInt() == 2 && Protocol.u16(it, 2 + 16) == Sequencer.STATE_CMD }
            .map { Protocol.u16(it, 2 + 16 + 2) to Protocol.u16(it, 2 + 16 + 4) } // (flags, position)
        waitUntil("last position delivered") { states().lastOrNull()?.second == 199 }
        val got = states()
        assertTrue("expected coalescing, got ${got.size} entries", got.size < 100)
        assertEquals("positions only move forward", got.map { it.second }.sorted(), got.map { it.second })
        // The newest position before the flag change survives, and so does the flag change.
        assertEquals(99, got.last { it.first == 7 }.second)
        assertTrue(got.any { it.first == 8 })
        waitUntil("guest caught up") { g.rec.frames.size == hostRec.frames.size }
        assertEquals(hostRec.snapshot(), g.rec.snapshot())
        assertClean(net, hostRec, g.rec)
    }

    @Test fun romMismatchIsFatalWithAClearMessage() {
        val net = net()
        val (room, hostRec) = host(net)

        // Different ROM entirely: the invite's fingerprint catches it before connecting.
        val other = guest(net, "Eve", 1, rom = RomId("f".repeat(40), "OTHER", "XXXX", 1))
        assertTrue("${other.rec.fatal}", other.rec.fatal?.contains("different ROM") == true)
        assertEquals(0L, net.connects.get())

        // Same 8-byte prefix, different SHA-1: only the host's full check catches it.
        val sneaky = RomId(TEST_ROM.sha1.take(16) + "f".repeat(24), "POKEMON EMER", "BPEE", TEST_ROM.size)
        val g = guest(net, "Mal", 2, rom = sneaky)
        waitUntil("refused") { g.rec.fatal != null }
        val msg = g.rec.fatal!!
        assertTrue(msg, msg.contains("Your ROM is different") && msg.contains("POKEMON EMER") && msg.contains(TEST_ROM.sha1.take(12)))
        assertTrue(g.rec.joins.isEmpty())
        assertNull(g.rec.lost)
        assertEquals(1, room.players.size)
        assertEquals(1, hostRec.joins.size)
        assertTrue(hostRec.left.isEmpty())
        assertClean(net, hostRec, g.rec)
    }

    @Test fun fullRoomRefusesWithAClearMessage() {
        val net = net()
        val (room, hostRec) = host(net, max = 2)
        val g1 = guest(net, "G1", 1, expectLinkId = 1)
        val g2 = guest(net, "G2", 2)
        waitUntil("refused") { g2.rec.fatal != null }
        assertTrue(g2.rec.fatal!!, g2.rec.fatal!!.contains("full"))
        Thread.sleep(50)
        assertNull(g1.rec.lost)
        assertEquals(listOf("Amy", "G1"), room.players.map { it.name })
        assertEquals(listOf("Amy", "G1"), hostRec.players.map { it.name })
        assertClean(net, hostRec, g1.rec, g2.rec)
    }

    @Test fun middlePlayerLeavesAndTheRestAreRenumbered() {
        val net = net(latencyMs = 10, jitterMs = 10)
        val (room, hostRec) = host(net)
        val g1 = guest(net, "G1", 1, expectLinkId = 1)
        val g2 = guest(net, "G2", 2, expectLinkId = 2)
        val g3 = guest(net, "G3", 3, expectLinkId = 3)
        val stay = listOf(hostRec, g1.rec, g3.rec)
        waitUntil("4 players") { stay.all { it.players.size == 4 } }

        // Both G2 and G3 have a backlog queued when G2 leaves: G2's is dropped,
        // G3's moves with G3 from slot 3 to slot 2.
        for (n in 1..60) g2.session.sendLink(gbaCommand(command(2, n)))
        for (n in 1..60) g3.session.sendLink(gbaCommand(command(3, n)))
        g2.session.leave()
        waitUntil("everyone heard G2 left") { stay.all { it.left == listOf("G2") } }
        waitUntil("rosters updated") { stay.all { it.players.size == 3 } }
        val expected = listOf(RoomPlayer(0, "Amy", true), RoomPlayer(1, "G1", true), RoomPlayer(2, "G3", true))
        for (r in stay) assertEquals(r.label, expected, r.players)
        assertEquals(1, g1.rec.linkId)
        assertEquals(2, g3.rec.linkId) // renumbered from 3
        assertEquals(expected, room.players)

        val g3ids = (1..60).map { 0x4000 + it }
        fun split(): Pair<LinkRecorder, LinkRecorder> {
            val all = hostRec.snapshot()
            val at = all.indexOf("0302") // LEFT, player 2
            assertTrue("LEFT frame for player 2", at >= 0)
            return LinkRecorder("before").also { it.frames.addAll(all.subList(0, at)) } to
                LinkRecorder("after").also { it.frames.addAll(all.subList(at + 1, all.size)) }
        }
        waitUntil("G3's backlog drained", 5000) { split().let { (b, a) -> b.commandsFrom(3) + a.commandsFrom(2) == g3ids } }
        val (before, after) = split()
        assertTrue("G2 still had a backlog", before.commandsFrom(2).size < 60)
        assertTrue("G3 still had a backlog", after.commandsFrom(2).isNotEmpty())
        assertEquals((1..before.commandsFrom(2).size).map { 0x3000 + it }, before.commandsFrom(2))
        assertTrue(after.commandsFrom(3).isEmpty())
        for (r in stay) assertTrue(r.label, "0302" in r.snapshot())

        // G3 keeps speaking in slot 2.
        g3.session.sendLink(gbaCommand(command(3, 61)))
        waitUntil("command from new slot 2") { split().second.commandsFrom(2).lastOrNull() == 0x4000 + 61 }
        waitUntil("guests caught up") { listOf(g1, g3).all { it.rec.frames.size == hostRec.frames.size } }
        assertEquals(hostRec.snapshot(), g1.rec.snapshot())
        assertEquals(hostRec.snapshot(), g3.rec.snapshot())

        // The leaver hears nothing more and its link traffic goes nowhere.
        assertNull(g2.rec.lost)
        assertNull(g2.rec.fatal)
        g2.session.sendLink(gbaHandshake(0x7777))
        Thread.sleep(100)
        assertFalse(hostRec.handshakes().any { it.second == 0x7777 })
        assertTrue(logs.toString(), logs.any { it.contains("G2 left") && it.contains("(left)") })
        for (r in stay) assertNull(r.lost)
        assertClean(net, hostRec, g1.rec, g2.rec, g3.rec)
    }

    @Test fun lostConnectionRemovesThePlayer() {
        val net = net()
        val (room, hostRec) = host(net)
        val g1 = guest(net, "G1", 1, expectLinkId = 1)
        val g2 = guest(net, "G2", 2, expectLinkId = 2)
        net.cut(g1.transport.pipes.single(), "timeout")
        waitUntil("G1 lost") { g1.rec.lost != null }
        assertEquals("timeout", g1.rec.lost)
        assertNull(g1.rec.fatal)
        waitUntil("G2 renumbered") { g2.rec.linkId == 1 }
        assertEquals(listOf("G1"), hostRec.left)
        assertEquals(listOf("G1"), g2.rec.left)
        assertEquals(listOf("Amy", "G2"), room.players.map { it.name })
        assertTrue("0301" in hostRec.snapshot())
        assertTrue(logs.toString(), logs.any { it.contains("connection lost: timeout") })
        assertClean(net, hostRec, g1.rec, g2.rec)
    }

    @Test fun hostClosingEndsTheSessionForGuests() {
        val net = net(latencyMs = 5)
        val (room, hostRec) = host(net)
        val guests = (1..2).map { guest(net, "G$it", it.toLong(), expectLinkId = it) }
        room.close()
        waitUntil("guests lost the session") { guests.all { it.rec.lost != null } }
        for (g in guests) {
            assertTrue(g.rec.lost!!, g.rec.lost!!.contains("closed"))
            assertNull(g.rec.fatal)
        }
        waitUntil("sequencer stopped") { !room.running }
        val (_, pipe) = net.pair("late", "late-host")
        assertNull("a closed room accepts nobody", room.acceptor(99, pipe))
        room.sendLink(gbaHandshake(1)) // ignored, no crash
        assertTrue(hostRec.left.isEmpty())
        assertClean(net, hostRec, *guests.map { it.rec }.toTypedArray())
    }

    @Test fun longNamesStillFitInOneMessage() {
        val net = net()
        val rec = LinkRecorder("host")
        val room = HostRoom("😀".repeat(100), "é\"".repeat(50), TEST_ROM, 4, rec) { logs.add(it) }.also { rooms.add(it) }
        net.hostTransport().setAcceptor(room::acceptor)
        assertEquals("😀".repeat(16), room.roomName)
        val guests = (1..3).map { guest(net, "\"😀".repeat(30), it.toLong(), expectLinkId = it) }
        waitUntil("rosters") { guests.all { it.rec.players.size == 4 } }
        assertEquals(room.roomName, guests[2].rec.joins.last().first)
        assertTrue(guests[2].rec.players.all { it.name.length <= Protocol.MAX_NAME })
        assertClean(net, rec, *guests.map { it.rec }.toTypedArray())
    }

    @Test fun sequencerRunsAtGbaFrameRate() {
        val net = net()
        val (room, hostRec) = host(net)
        for (i in 1..600) room.sendLink(gbaCommand(intArrayOf(i, 0, 0, 0, 0, 0, 0, 0)))
        Thread.sleep(300) // settle
        val t0 = System.nanoTime()
        val n0 = hostRec.frames.size
        Thread.sleep(3000)
        val n1 = hostRec.frames.size
        val rate = (n1 - n0) / ((System.nanoTime() - t0) / 1e9)
        assertTrue("rate was $rate entries/s, want ~59.73", rate in 57.0..62.5)
        val cmds = hostRec.commandsFrom(0)
        assertEquals((1..cmds.size).toList(), cmds) // in order, none lost
    }

    @Test fun anIdleRoomSequencesACommandWithoutWaitingForATick() {
        val net = net()
        val (room, hostRec) = host(net)
        Thread.sleep(200)
        val lat = ArrayList<Long>()
        for (i in 1..20) {
            val before = hostRec.frames.size
            val t = System.nanoTime()
            room.sendLink(gbaCommand(intArrayOf(i, 0, 0, 0, 0, 0, 0, 0)))
            waitUntil("entry $i") { hostRec.frames.size > before }
            lat.add((System.nanoTime() - t) / 1_000_000)
            Thread.sleep(40) // more than a frame, so each command meets an idle sequencer
        }
        lat.sort()
        assertTrue("median ${lat[10]} ms (a fixed tick would average ~8 ms more)", lat[10] <= 3)
    }

    @Test fun unreachableHostIsFatal() {
        val net = net()
        val g = guest(net, "G1", 1) // nobody hosting on this network
        waitUntil("gave up") { g.rec.fatal != null }
        assertTrue(g.rec.fatal!!, g.rec.fatal!!.contains("Couldn't reach Amy's phone"))
        assertNull(g.rec.lost)
    }

    @Test fun linkTrafficBeforeJoiningIsDropped() {
        val net = net(latencyMs = 20)
        val (_, hostRec) = host(net)
        val g = guest(net, "G1", 1)
        g.session.sendLink(gbaHandshake(0x1234)) // still connecting
        waitUntil("joined") { g.rec.linkId == 1 }
        g.session.sendLink(gbaHandshake(0x4321))
        waitUntil("handshake after joining") { hostRec.handshakes().isNotEmpty() }
        Thread.sleep(100)
        assertEquals(listOf(1 to 0x4321), hostRec.handshakes())
        assertClean(net, hostRec, g.rec)
    }

    @Test fun leavingWhileConnectingIsQuiet() {
        val net = net(latencyMs = 30)
        val (room, hostRec) = host(net)
        val g = guest(net, "G1", 1)
        g.session.close()
        assertTrue(g.transport.closed)
        Thread.sleep(200)
        assertTrue(g.rec.joins.isEmpty())
        assertNull(g.rec.fatal)
        assertNull(g.rec.lost)
        assertEquals(1, room.players.size)
        assertEquals(1, hostRec.joins.size)
    }

    /** Joins that a real GuestSession never sends, straight onto a pipe. */
    @Test fun badJoinsAreRefused() {
        val net = net()
        val (room, hostRec) = host(net)
        fun raw(connId: Long): Pair<MemNet.End, MutableList<Control?>> {
            val (g, h) = net.pair("raw$connId", "host<-raw$connId")
            val got: MutableList<Control?> = Collections.synchronizedList(ArrayList())
            g.handler = object : PipeHandler {
                override fun onMessage(pipe: Pipe, message: ByteArray) {
                    got.add(Protocol.decodeControl(message))
                }
                override fun onClosed(pipe: Pipe, reason: String) {}
            }
            h.handler = room.acceptor(connId, h)!!
            return g to got
        }

        val (p1, got1) = raw(1)
        p1.send(Protocol.linkUp(gbaHandshake(0x5555))!!) // not a member yet: ignored
        p1.send(Protocol.encode(Control.Join("Old", TEST_ROM, 2)))
        waitUntil("proto error") { got1.isNotEmpty() }
        assertEquals("proto", (got1.single() as Control.Error).code)

        val (p2, got2) = raw(2)
        p2.send(Protocol.encode(Control.Join("NoRom", null, 1)))
        waitUntil("rom error") { got2.isNotEmpty() }
        assertEquals("rom_mismatch", (got2.single() as Control.Error).code)
        p2.send(Protocol.encode(Control.Join("NoRom", TEST_ROM, 1))) // refused connections stay refused
        Thread.sleep(100)
        assertEquals(1, got2.size)

        val (p3, got3) = raw(3)
        p3.send(Protocol.encode(Control.Join("Ok", TEST_ROM, 1)))
        p3.send(Protocol.encode(Control.Join("Again", TEST_ROM, 1))) // duplicate join ignored
        p3.send(byteArrayOf(1) + "garbage".toByteArray())
        p3.send(byteArrayOf(9, 9, 9))
        p3.send(byteArrayOf(3, 2, 1)) // short LINK_UP
        waitUntil("welcome") { got3.isNotEmpty() }
        Thread.sleep(100)
        val welcome = got3.single() as Control.Welcome
        assertEquals(1, welcome.linkId)
        assertEquals(listOf("Amy", "Ok"), welcome.players.map { it.name })
        assertEquals(2, room.players.size)
        assertTrue(hostRec.handshakes().isEmpty())

        // Refused pipes are hung up after a grace period.
        waitUntil("refused pipes closed", 4000) { p1.closed && p2.closed }
        assertFalse(p3.closed)
        assertClean(net, hostRec)
    }

    /** A transport that holds its own lock in send() and while calling handlers must not deadlock the room. */
    @Test fun transportHoldingItsLockCannotDeadlockTheRoom() {
        val net = net(latencyMs = 1, jitterMs = 2)
        net.lockedTransport = true
        val (room, hostRec) = host(net)
        val guests = (1..3).map { guest(net, "G$it", it.toLong(), expectLinkId = it) }
        val senders: List<(ByteArray) -> Unit> = listOf<(ByteArray) -> Unit>(room::sendLink) + guests.map { g -> { b: ByteArray -> g.session.sendLink(b) } }
        senders.mapIndexed { p, send ->
            thread {
                for (n in 1..30) {
                    send(gbaHandshake(n))
                    send(gbaCommand(command(p, n)))
                }
            }
        }.forEach { it.join(5000) }
        waitUntil("all commands sequenced", 10_000) { (0..3).all { hostRec.commandsFrom(it).size == 30 } }
        waitUntil("guests caught up") { guests.all { it.rec.frames.size == hostRec.frames.size } }
        for (g in guests) assertEquals(hostRec.snapshot(), g.rec.snapshot())
        assertClean(net, hostRec, *guests.map { it.rec }.toTypedArray())
    }

    /** A host listener that answers from inside onLinkFrame must not reorder its own stream. */
    @Test fun reentrantHostListenerKeepsOrder() {
        val net = net(latencyMs = 5, jitterMs = 5)
        lateinit var room: HostRoom
        val rec = object : LinkListenerProxy(LinkRecorder("host")) {
            override fun onLinkFrame(frame: ByteArray) {
                super.onLinkFrame(frame)
                if (frame[0].toInt() == 1 && frame[1].toInt() == 1) room.sendLink(gbaHandshake(0x9000 + Protocol.u16(frame, 2)))
            }
        }
        room = HostRoom("Amy's room", "Amy", TEST_ROM, 4, rec) { logs.add(it) }.also { rooms.add(it) }
        net.hostTransport().setAcceptor(room::acceptor)
        val g = guest(net, "G1", 1, expectLinkId = 1)
        for (i in 1..20) g.session.sendLink(gbaHandshake(i))
        waitUntil("echoes") { rec.inner.handshakes().size == 40 }
        waitUntil("guest caught up") { g.rec.frames.size == 40 }
        assertEquals(rec.inner.snapshot(), g.rec.snapshot())
        assertClean(net, rec.inner, g.rec)
    }

    /** A pipe like UdpTransport's (send rejects oversize messages) that can also be told to fail every send. */
    private class RawPipe(val label: String) : Pipe {
        val got: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        @Volatile var failing = false
        @Volatile var closedWith: String? = null
        override val path get() = "raw:$label"
        override fun send(message: ByteArray) {
            require(message.size <= Pipe.MAX_MESSAGE) { "message of ${message.size} bytes" }
            check(!failing) { "send failed" }
            got.add(message.copyOf())
        }
        override fun close(reason: String) {
            if (closedWith == null) closedWith = reason
        }
        fun controls() = synchronized(got) { got.toList() }.mapNotNull { Protocol.decodeControl(it) }
        fun frames() = synchronized(got) { got.toList() }.filter { it[0] == Protocol.LINK_DOWN }
    }

    private fun rawJoin(room: HostRoom, connId: Long, name: String) = RawPipe(name).also { p ->
        room.acceptor(connId, p)!!.onMessage(p, Protocol.encode(Control.Join(name, TEST_ROM, Protocol.VERSION)))
        waitUntil("$name welcomed") { p.controls().any { it is Control.Welcome } }
    }

    /** A pipe whose sends throw costs the others nothing: frames, the host's own GBA, and the goodbye. */
    @Test fun oneFailingPipeCostsNobodyElse() {
        val hostRec = LinkRecorder("host")
        val room = HostRoom("Amy's room", "Amy", TEST_ROM, 4, hostRec) { logs.add(it) }.also { rooms.add(it) }
        val g1 = rawJoin(room, 1, "G1")
        val g2 = rawJoin(room, 2, "G2")
        val g3 = rawJoin(room, 3, "G3")
        g2.failing = true // G3 comes after it in every loop over the members
        room.sendLink(gbaHandshake(0x7777))
        waitUntil("host's own frame") { hostRec.handshakes() == listOf(0 to 0x7777) }
        waitUntil("frames to the healthy pipes") { g1.frames().size == 1 && g3.frames().size == 1 }

        // A reason far over a pipe message (e.g. an exception's text) is cut, and every pipe still closes.
        room.close("Network error: " + "x".repeat(3000))
        waitUntil("every pipe closed") { listOf(g1, g2, g3).all { it.closedWith != null } }
        for (g in listOf(g1, g3)) {
            val bye = g.controls().filterIsInstance<Control.Closed>().single()
            assertTrue(bye.reason, bye.reason.startsWith("Network error: xxx") && bye.reason.length <= 300)
        }
        assertTrue("the failing pipe's sends were the only failures: $logs",
            logs.filter { it.contains("callback failed") }.all { it.contains("send failed") })
    }

    /** close() right after construction (e.g. the transport failed to open): the host never hears it joined. */
    @Test fun closingAtOnceSendsNoJoin() {
        repeat(50) {
            val rec = LinkRecorder("host")
            val room = HostRoom("Amy's room", "Amy", TEST_ROM, 4, rec)
            room.close()
            waitUntil("sequencer stopped") { !room.running }
            assertTrue(rec.joins.isEmpty())
        }
        // Delivered, as usual, when the room stays open.
        val (_, rec) = host(net())
        assertEquals(listOf(RoomPlayer(0, "Amy", true)), rec.players)
    }

    /** A connId names one attempt: reusing it fails at once (UdpTransport would ignore it), a fresh one joins. */
    @Test fun reusedConnIdFailsAtOnce() {
        val net = net()
        host(net)
        val t = net.guestTransport()
        val first = LinkRecorder("try1")
        val s = GuestSession(invite, "Ben", TEST_ROM, t, 42, first)
        s.start()
        waitUntil("joined") { first.linkId == 1 }
        s.leave()
        val again = LinkRecorder("try2")
        GuestSession(invite, "Ben", TEST_ROM, t, 42, again).start()
        assertTrue("${again.fatal}", again.fatal?.contains("already used") == true)
        assertEquals(1L, net.connects.get()) // never handed to the transport
        val fresh = LinkRecorder("try3")
        GuestSession(invite, "Ben", TEST_ROM, t, 43, fresh).start()
        waitUntil("fresh connId joined") { fresh.linkId == 1 }
        // The same id on another transport is another attempt.
        val other = LinkRecorder("other")
        GuestSession(invite, "Cy", TEST_ROM, net.guestTransport(), 42, other).start()
        waitUntil("other transport joined") { other.linkId == 2 }
    }

    private open class LinkListenerProxy(val inner: LinkRecorder) : LinkListener by inner
}
