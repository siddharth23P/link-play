package com.linkplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Adversarial review of HostRoom / GuestSession. Tests named *Bug* fail on the
 * reviewed code and describe the defect; the others pin behaviour that held up.
 */
class RoomReviewTest {
    /** A pipe that behaves like UdpTransport's: send() rejects messages over Pipe.MAX_MESSAGE. */
    private class StrictPipe(val label: String) : Pipe {
        val sent: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        @Volatile var closedWith: String? = null
        override val path get() = "strict:$label"
        override fun send(message: ByteArray) {
            require(message.size <= Pipe.MAX_MESSAGE) { "message of ${message.size} bytes is over ${Pipe.MAX_MESSAGE}" }
            if (closedWith == null) sent.add(message.copyOf())
        }
        override fun close(reason: String) {
            if (closedWith == null) closedWith = reason
        }
        fun controls() = synchronized(sent) { sent.toList() }.mapNotNull { Protocol.decodeControl(it) }
    }

    private fun joinRaw(room: HostRoom, connId: Long, name: String): Pair<StrictPipe, PipeHandler> {
        val pipe = StrictPipe(name)
        val h = room.acceptor(connId, pipe)!!
        h.onMessage(pipe, Protocol.encode(Control.Join(name, TEST_ROM, Protocol.VERSION)))
        waitUntil("$name welcomed") { pipe.controls().any { it is Control.Welcome } }
        return pipe to h
    }

    /**
     * BUG: HostRoom starts its sequencer thread in the constructor and that thread's first act is an
     * unconditional local.onJoined. If the UI closes the room right away (cancel, transport failed to
     * open...), the host still gets onJoined AFTER close() returned, i.e. "you are in the room" for a
     * room that is already closed (MainActivity would attach the cable and switch to the game screen).
     */
    @Test fun bugOnJoinedArrivesAfterClose() {
        var late = 0
        repeat(100) {
            val closedReturned = AtomicBoolean()
            val lateJoins = AtomicInteger()
            val rec = object : LinkListener by LinkRecorder("host") {
                override fun onJoined(roomName: String, linkId: Int, players: List<RoomPlayer>) {
                    if (closedReturned.get()) lateJoins.incrementAndGet()
                }
            }
            val room = HostRoom("r", "Amy", TEST_ROM, 2, rec)
            room.close()
            closedReturned.set(true)
            waitUntil("sequencer stopped") { !room.running }
            late += lateJoins.get()
        }
        assertEquals("rooms that reported onJoined after close() returned (out of 100)", 0, late)
    }

    /**
     * BUG: close(reason) builds one {"t":"closed"} message and sends it to every pipe inside ONE outbox
     * action. UdpTransport.send throws on messages over 1024 bytes, so a long reason (e.g. one that
     * embeds an exception message) throws on the first pipe and the action is abandoned: no guest is
     * told, and no pipe is closed. The same all-or-nothing loop is used by broadcast(), where one
     * throwing pipe would also skip the host's own onLinkFrame.
     */
    @Test fun bugLongCloseReasonLeavesEveryPipeOpen() {
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val room = HostRoom("r", "Amy", TEST_ROM, 4, LinkRecorder("host")) { logs.add(it) }
        try {
            val (p1, _) = joinRaw(room, 1, "G1")
            val (p2, _) = joinRaw(room, 2, "G2")
            room.close("Network error: " + "x".repeat(1100))
            waitUntil("sequencer stopped") { !room.running }
            val problems = listOfNotNull(
                "G1's pipe was never closed".takeIf { p1.closedWith == null },
                "G2's pipe was never closed".takeIf { p2.closedWith == null },
                "G1 never heard the room closed".takeIf { p1.controls().none { it is Control.Closed } },
                "G2 never heard the room closed".takeIf { p2.controls().none { it is Control.Closed } },
            ) + logs.filter { it.contains("callback failed") }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally {
            room.close()
        }
    }

    /** Hostile control / link messages from a pipe that has the room key: nothing may break the room. */
    @Test fun hostileMessagesNeverBreakTheRoom() {
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val hostRec = LinkRecorder("host")
        val room = HostRoom("r", "Amy", TEST_ROM, 4, hostRec) { logs.add(it) }
        try {
            val evil = StrictPipe("evil")
            val h = room.acceptor(66, evil)!!
            val rnd = Random(7)
            val templates = listOf(
                """{"t":"join","name":[1,2],"rom":"x","proto":"1"}""",
                """{"t":"join","name":{"a":1},"rom":{"sha1":12},"proto":1e400}""",
                """{"t":"join","rom":{"sha1":"${TEST_ROM.sha1}","size":"big"},"proto":4294967297}""",
                """{"t":"leave","t":"join"}""",
                """{"t":"welcome","room":"x","linkId":0,"players":[]}""",
                """{"t":"roster","linkId":-5,"players":[{"linkId":"a"}]}""",
                """{"t":"closed","reason":null}""",
                "[" .repeat(500) + "]".repeat(500),
                """{"t":"join","name":"𐀀\ud800","proto":1}""",
                """{"t":""" + "\"" + "a".repeat(1000) + "\"}",
            )
            repeat(3000) {
                val msg = when (rnd.nextInt(4)) {
                    0 -> byteArrayOf(Protocol.CONTROL) + templates.random(rnd).toByteArray()
                    1 -> byteArrayOf(Protocol.CONTROL) + templates.random(rnd).toByteArray().let { b ->
                        b.copyOf(rnd.nextInt(b.size + 1)).also { if (it.isNotEmpty()) it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
                    }
                    2 -> ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt(256).toByte() }.also { if (it.isNotEmpty()) it[0] = 3 }
                    else -> ByteArray(rnd.nextInt(0, 1100)) { rnd.nextInt(256).toByte() }
                }
                h.onMessage(evil, msg)
            }
            // The room still works for a real guest.
            val (good, _) = joinRaw(room, 1, "G1")
            assertTrue(good.controls().first() is Control.Welcome)
            assertTrue(logs.none { it.contains("callback failed") })
            assertTrue(room.running)
            assertTrue("players: ${room.players}", room.players.size in 2..3) // evil may have joined once, by luck
        } finally {
            room.close()
        }
    }

    /** A guest fed garbage before and after joining reports at most one ending and never a bad frame. */
    @Test fun guestSurvivesGarbageAndEndsOnce() {
        val rnd = Random(3)
        val invite = Codes.newInvite("Amy", "Room", TEST_ROM, 4, emptyList())
        repeat(200) { round ->
            val rec = LinkRecorder("g")
            val pipe = StrictPipe("g$round")
            var handler: PipeHandler? = null
            val transport = object : Transport {
                override val port = 1
                override fun setAcceptor(acceptor: (connId: Long, pipe: Pipe) -> PipeHandler?) {}
                override fun probe(connId: Long, candidates: List<Candidate>) {}
                override fun connect(connId: Long, hostCandidates: List<Candidate>, handler: PipeHandler, onOpen: (Pipe) -> Unit) {
                    onOpen(pipe)
                    handler.let { h -> handlerSink(h) }
                }
                fun handlerSink(h: PipeHandler) { handler = h }
                override fun stun(server: java.net.InetSocketAddress, timeoutMs: Int): java.net.InetSocketAddress? = null
                override fun close() {}
            }
            GuestSession(invite, "G", TEST_ROM, transport, 5, rec).start()
            val h = handler!!
            val welcome = Protocol.encode(Control.Welcome("Room", 1, listOf(RoomPlayer(0, "Amy", true), RoomPlayer(1, "G", true))))
            repeat(60) { i ->
                val m = when (rnd.nextInt(6)) {
                    0 -> welcome
                    1 -> Protocol.linkDown(byteArrayOf(3, rnd.nextInt(-2, 6).toByte()))
                    2 -> Protocol.encode(Control.Roster(rnd.nextInt(-1, 5), emptyList()))
                    3 -> ByteArray(rnd.nextInt(0, 80)) { rnd.nextInt(256).toByte() }
                    4 -> if (i > 50) Protocol.encode(Control.Closed("bye")) else welcome
                    else -> Protocol.linkDown(ByteArray(66).also { it[0] = 2 })
                }
                h.onMessage(pipe, m)
            }
            h.onClosed(pipe, "timeout")
            assertTrue("round $round: fatal=${rec.fatal} lost=${rec.lost}", rec.fatal == null || rec.lost == null)
            assertTrue(!rec.badFrame)
        }
    }

    /** A guest's GBA traffic before the welcome never reaches the host's sequencer, even from a raw pipe. */
    @Test fun linkUpBeforeJoinIsIgnoredByTheHost() {
        val hostRec = LinkRecorder("host")
        val room = HostRoom("r", "Amy", TEST_ROM, 4, hostRec)
        try {
            val pipe = StrictPipe("early")
            val h = room.acceptor(1, pipe)!!
            repeat(50) {
                h.onMessage(pipe, Protocol.linkUp(gbaHandshake(0x1111))!!)
                h.onMessage(pipe, Protocol.linkUp(gbaCommand(intArrayOf(0x2222, 0, 0, 0, 0, 0, 0, 0)))!!)
            }
            h.onMessage(pipe, Protocol.encode(Control.Join("G", TEST_ROM, 1)))
            waitUntil("welcome") { pipe.controls().any { it is Control.Welcome } }
            Thread.sleep(150)
            assertTrue(hostRec.handshakes().isEmpty())
            assertTrue(hostRec.commandsFrom(1).isEmpty())
        } finally {
            room.close()
        }
    }

    /** Leaving and dying in every order: the roster is always the joined, still-connected guests in join order. */
    @Test fun membersAreAlwaysRemovedAndRosterStaysContiguous() {
        val hostRec = LinkRecorder("host")
        val room = HostRoom("r", "Amy", TEST_ROM, 4, hostRec)
        try {
            val rnd = Random(11)
            val live = ArrayList<Pair<StrictPipe, PipeHandler>>()
            var next = 1L
            repeat(300) {
                when {
                    live.size < 3 && rnd.nextBoolean() -> {
                        val name = "G${next}"
                        live.add(joinRaw(room, next++, name))
                    }
                    live.isNotEmpty() -> {
                        val (p, h) = live.removeAt(rnd.nextInt(live.size))
                        if (rnd.nextBoolean()) h.onMessage(p, Protocol.encode(Control.Leave)) else h.onClosed(p, "timeout")
                    }
                }
                val expected = listOf("Amy") + live.map { it.first.label }
                assertEquals(expected, room.players.map { it.name })
                assertEquals(expected.indices.toList(), room.players.map { it.linkId })
            }
            // Every guest still in the room was last told its own, current index.
            fun lastLinkId(p: StrictPipe) = when (val last = p.controls().last { it is Control.Welcome || it is Control.Roster }) {
                is Control.Roster -> last.linkId
                is Control.Welcome -> last.linkId
                else -> -1
            }
            waitUntil("rosters delivered") { live.withIndex().all { (i, pair) -> lastLinkId(pair.first) == i + 1 } }
            assertNull(hostRec.fatal)
        } finally {
            room.close()
        }
    }
}
