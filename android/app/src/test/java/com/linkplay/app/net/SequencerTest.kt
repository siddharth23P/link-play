package com.linkplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sequencer on a fake clock: exactly relay.js Room.onCommand / Room.pump. */
class SequencerTest {
    private fun cmd(n: Int) = intArrayOf(n, 1, 2, 3, 4, 5, 6, 7)

    // Quetzal state packet: words 1, 5, 7 and the high byte of 6 are flags.
    private fun state(pos: Int, flags: Int = 7) = intArrayOf(Sequencer.STATE_CMD, flags, pos, pos, 0, 3, 0x0100 or (pos and 0xff), 9)

    private fun word(entry: ByteArray, player: Int, w: Int) = Protocol.u16(entry, (player * 8 + w) * 2)

    private fun drain(s: Sequencer, from: Long = s.nextTick, ticks: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var now = from
        repeat(ticks) {
            s.pump(now) { out.add(it) }
            now += FRAME_NS
        }
        return out
    }

    @Test fun oneCommandPerPlayerPerTickInArrivalOrder() {
        val s = Sequencer(0)
        s.onCommand(0, cmd(10))
        s.onCommand(2, cmd(30))
        s.onCommand(0, cmd(11))
        val out = drain(s, 0, 4)
        assertEquals(2, out.size) // no entry for ticks with nothing queued
        assertEquals(10, word(out[0], 0, 0))
        assertEquals(30, word(out[0], 2, 0))
        assertEquals(0, word(out[0], 1, 0))
        assertEquals(11, word(out[1], 0, 0))
        assertEquals(0, word(out[1], 2, 0))
        assertEquals(7, word(out[0], 0, 7))
        assertEquals(64, out[0].size)
    }

    @Test fun stateReplaceableCoalescesWhenBackedUp() {
        val s = Sequencer(0)
        s.onCommand(0, cmd(1))
        for (pos in 0 until 50) s.onCommand(0, state(pos))
        // [cmd, state0] then every newer position replaced the queued one.
        assertEquals(2, s.queued(0))
        // A changed flag (e.g. "wants to battle") is never merged away.
        s.onCommand(0, state(60, flags = 8))
        s.onCommand(0, state(61, flags = 8))
        assertEquals(3, s.queued(0))
        val out = drain(s, 0, 5)
        assertEquals(listOf(1, Sequencer.STATE_CMD, Sequencer.STATE_CMD), out.map { word(it, 0, 0) })
        assertEquals(49, word(out[1], 0, 2)) // the newest position survived
        assertEquals(61, word(out[2], 0, 2))
        assertEquals(8, word(out[2], 0, 1))

        // With only one queued there is nothing to coalesce into: relay.js needs q.length >= 2.
        val t = Sequencer(0)
        t.onCommand(1, state(1))
        t.onCommand(1, state(2))
        assertEquals(2, t.queued(1))
        assertTrue(Sequencer.stateReplaceable(state(1), state(2)))
        assertFalse(Sequencer.stateReplaceable(state(1), state(2, flags = 9)))
        assertFalse(Sequencer.stateReplaceable(cmd(1), cmd(2)))
        assertFalse(Sequencer.stateReplaceable(state(1), state(2).also { it[6] = 0x0200 }))
    }

    @Test fun fifoLimitDropsTheOverflow() {
        val s = Sequencer(0)
        for (i in 1..700) s.onCommand(3, cmd(i))
        assertEquals(Sequencer.FIFO_LIMIT, s.queued(3))
        val out = drain(s, 0, 700)
        assertEquals(600, out.size)
        assertEquals((1..600).toList(), out.map { word(it, 3, 0) })
    }

    @Test fun rateIsOneEntryPerGbaFrame() {
        // Pumped every 4 ms: 10 simulated seconds of a permanent backlog.
        val s = Sequencer(0)
        for (i in 1..700) s.onCommand(0, cmd(i))
        var entries = 0
        var last = Long.MIN_VALUE
        var now = 0L
        while (now <= 10_000_000_000L) {
            s.pump(now) {
                entries++
                if (last != Long.MIN_VALUE) assertTrue(now - last >= FRAME_NS - 4_000_000)
                last = now
            }
            now += 4_000_000
        }
        assertTrue("$entries", entries in 596..600) // 59.73/s, no burst
    }

    @Test fun anIdleSequencerEmitsTheCommandAtOnce() {
        val s = Sequencer(0)
        val t0 = 5 * FRAME_NS + 123
        var out = 0
        s.onCommand(0, cmd(1)); s.pump(t0) { out++ }
        assertEquals(1, out) // no wait for a tick
        s.onCommand(0, cmd(2)); s.pump(t0 + 1_000_000) { out++ }
        assertEquals(1, out) // too soon: held ...
        assertEquals(t0 + FRAME_NS, s.nextTick)
        s.pump(t0 + FRAME_NS) { out++ }
        assertEquals(2, out) // ... and out exactly one frame after the last
        s.pump(t0 + 5 * FRAME_NS) { out++ }
        assertEquals(2, out) // nothing queued: nothing emitted
        s.onCommand(1, cmd(3)); s.pump(t0 + 5 * FRAME_NS + 7) { out++ }
        assertEquals(3, out)
    }

    @Test fun aStallDoesNotBurst() {
        val s = Sequencer(0)
        for (i in 1..100) s.onCommand(0, cmd(i))
        var n = 0
        s.pump(2_000_000_000L) { n++ }
        s.pump(2_000_000_000L) { n++ }
        assertEquals(1, n)
    }

    @Test fun removeShiftsQueuesDown() {
        val s = Sequencer(0)
        s.onCommand(1, cmd(100))
        s.onCommand(2, cmd(200))
        s.onCommand(3, cmd(300))
        s.remove(2) // player 3 (index 2) left: their command is dropped, index 3 becomes 2
        val out = drain(s, 0, 1)
        assertEquals(100, word(out[0], 1, 0))
        assertEquals(300, word(out[0], 2, 0))
        assertEquals(0, word(out[0], 3, 0))
        assertEquals(0, s.queued(3))
    }
}
