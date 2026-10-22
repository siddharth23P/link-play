package com.linkplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

class ReliableStreamTest {
    private val ms = 1_000_000L

    /** One DATA body from [s], as the receiver parses it: (ack, [(seq, bytes)]). */
    private fun packet(s: ReliableStream, now: Long, room: Int = 1148 + 5): Pair<Int, List<Pair<Int, ByteArray>>> {
        val b = ByteBuffer.allocate(room)
        s.fill(b, now, room)
        b.flip()
        val ack = b.int
        val count = b.get().toInt() and 0xff
        return ack to List(count) {
            val seq = b.int
            seq to ByteArray(b.short.toInt()).also { m -> b.get(m) }
        }
    }

    @Test
    fun deliversInOrderOverAShuffledLossyLink() {
        val rnd = Random(7)
        val a = ReliableStream()
        val b = ReliableStream()
        val got = ArrayList<Int>()
        var now = 0L
        val inFlight = ArrayList<Pair<Long, Pair<Int, List<Pair<Int, ByteArray>>>>>() // (arrival, packet) a -> b
        val acks = ArrayList<Pair<Long, Int>>() // (arrival, ack) b -> a
        var sent = 0
        while (got.size < 3000) {
            now += ms
            while (sent < 3000 && a.backlog < 500) a.queue(ByteBuffer.allocate(4 + sent % 40).putInt(sent++).array())
            a.startRound()
            while (a.hasDue(now)) {
                val p = packet(a, now)
                if (p.second.isEmpty()) break
                repeat(if (rnd.nextInt(10) == 0) 2 else 1) { // duplicates
                    if (rnd.nextInt(5) != 0) inFlight.add(now + (40 + rnd.nextInt(80)) * ms to p) // 20% loss, reordering
                }
            }
            val arrived = inFlight.filter { it.first <= now }
            inFlight.removeAll(arrived.toSet())
            for ((_, p) in arrived) {
                for ((seq, m) in p.second) b.receive(seq, m) { got.add(ByteBuffer.wrap(it).int) }
                if (rnd.nextInt(5) != 0) acks.add(now + (40 + rnd.nextInt(80)) * ms to b.expected.toInt())
            }
            val acked = acks.filter { it.first <= now }
            acks.removeAll(acked.toSet())
            for ((_, ack) in acked) a.onAck(ack, now)
            assertTrue("stuck at ${got.size}", now < 600_000 * ms)
        }
        assertEquals((0 until 3000).toList(), got)
        assertTrue("srtt ${a.srttNs / ms} ms", a.srttNs in 80 * ms..400 * ms)
    }

    @Test
    fun rtoFollowsKarnFilteredSamples() {
        val s = ReliableStream()
        assertEquals(100 * ms, s.rtoNs)
        s.queue(byteArrayOf(1))
        s.startRound(); packet(s, 0)
        s.onAck(2, 4 * ms) // clean sample: 4 ms -> 1.15 x = 4.6 ms, clamped to 25 ms
        assertEquals(4 * ms, s.srttNs)
        assertEquals(25 * ms, s.rtoNs)

        s.queue(byteArrayOf(2))
        s.startRound(); packet(s, 10 * ms)
        s.startRound(); packet(s, 40 * ms) // RTO passed: resent
        s.onAck(3, 1000 * ms) // ambiguous: no sample
        assertEquals(4 * ms, s.srttNs)

        repeat(40) { i -> // slow link: srtt -> ~1 s, RTO clamps at 300 ms
            s.queue(byteArrayOf(3))
            s.startRound(); packet(s, i * 10_000 * ms)
            s.onAck(4 + i, i * 10_000 * ms + 1000 * ms)
        }
        assertEquals(300 * ms, s.rtoNs)
    }

    @Test
    fun timeoutsBackOffUntilACleanSample() {
        val s = ReliableStream()
        s.queue(byteArrayOf(1))
        s.startRound(); packet(s, 0)
        assertEquals(100 * ms, s.retransmitAt())
        s.startRound(); packet(s, 100 * ms) // timed out: resent, the RTO doubles
        assertEquals(200 * ms, s.rtoNs)
        assertEquals(300 * ms, s.retransmitAt())
        s.startRound(); packet(s, 300 * ms)
        assertEquals(300 * ms, s.rtoNs) // clamped
        s.onAck(2, 350 * ms) // ambiguous sample: stays backed off
        assertEquals(300 * ms, s.rtoNs)
        s.queue(byteArrayOf(2))
        s.startRound(); packet(s, 400 * ms)
        s.onAck(3, 450 * ms) // clean 50 ms sample
        assertEquals(50 * ms, s.srttNs)
        assertEquals(57_500_000L, s.rtoNs)
    }

    @Test
    fun resendsOnlyWhatIsDueAndFillsPacketsOldestFirst() {
        val s = ReliableStream()
        repeat(30) { s.queue(ByteArray(100) { _ -> it.toByte() }) }
        s.startRound()
        val first = packet(s, 0, 600)
        assertEquals((1..5).toList(), first.second.map { it.first }) // 5 x 106 bytes fit in 595
        val rest = generateSequence { packet(s, 0, 600).second.takeIf { it.isNotEmpty() } }.toList()
        assertEquals((6..30).toList(), rest.flatten().map { it.first })
        assertTrue(!s.hasDue(50 * ms))
        assertEquals(100 * ms, s.retransmitAt())
        s.onAck(11, 60 * ms) // 1..10 arrived
        assertEquals(20, s.backlog)
        s.startRound()
        assertEquals(11, packet(s, 100 * ms).second.first().first) // only 11..30 come back
        s.onAck(31, 120 * ms)
        assertTrue(s.allAcked)
    }

    @Test
    fun boundedReorderBufferAndDuplicates() {
        val r = ReliableStream(reorderWindow = 8)
        val got = ArrayList<Int>()
        r.receive(20, byteArrayOf(20)) { got.add(it[0].toInt()) } // too far ahead: dropped
        for (seq in listOf(3, 2, 2, 5, 4)) r.receive(seq, byteArrayOf(seq.toByte())) { got.add(it[0].toInt()) }
        assertEquals(emptyList<Int>(), got)
        r.receive(1, byteArrayOf(1)) { got.add(it[0].toInt()) }
        assertEquals(listOf(1, 2, 3, 4, 5), got)
        r.receive(3, byteArrayOf(3)) { got.add(it[0].toInt()) }
        assertEquals(6L, r.expected)
        assertEquals(5, got.size)
    }

    @Test
    fun acksForUnsentMessagesAreIgnored() {
        val s = ReliableStream()
        s.queue(byteArrayOf(1))
        s.startRound(); packet(s, 0)
        s.onAck(50, ms)
        assertEquals(1, s.backlog)
        s.onAck(2, ms)
        assertTrue(s.allAcked)
    }

    @Test
    fun aLostPacketIsRepeatedByTheNextOneLongBeforeTheRto() {
        val a = ReliableStream()
        val b = ReliableStream()
        a.queue(byteArrayOf(1))
        a.startRound(); packet(a, 0) // lost
        a.onAck(1, 0) // (no-op)
        a.queue(byteArrayOf(2))
        a.startRound()
        val p = packet(a, 16 * ms) // the next frame's message: carries the lost one again
        assertEquals(listOf(1, 2), p.second.map { it.first })
        val got = ArrayList<Int>()
        for ((seq, m) in p.second) b.receive(seq, m) { got.add(it[0].toInt()) }
        assertEquals(listOf(1, 2), got) // recovered 16 ms after the loss, not after the 100 ms RTO
        // At most two proactive copies, and the copies leave the RTT sampling clean.
        a.queue(byteArrayOf(3)); a.startRound(); packet(a, 20 * ms)
        a.queue(byteArrayOf(4)); a.startRound()
        assertEquals(listOf(2, 3, 4), packet(a, 30 * ms).second.map { it.first }) // 1 has had its two
        a.onAck(5, 40 * ms)
        assertTrue(a.allAcked)
    }

    @Test
    fun lossRecoveryWithoutFollowUpTrafficUsesATightRto() {
        val a = ReliableStream()
        a.queue(byteArrayOf(1)); a.startRound(); packet(a, 0); a.onAck(2, 30 * ms)
        assertEquals(30 * ms, a.srttNs)
        a.queue(byteArrayOf(2)); a.startRound(); packet(a, 100 * ms) // lost, nothing follows
        assertEquals(100 * ms + 34_500_000L, a.retransmitAt()) // ~1.15 x srtt
        a.startRound()
        assertEquals(listOf(2), packet(a, 135 * ms).second.map { it.first })
    }

    @Test
    fun orderedStreamUnderRandomLossStallsLessWithRepeats() {
        // One message per 16 ms frame over a 20 ms one-way link losing 10% of packets: the worst
        // delivery delay stays well under what an RTO-only recovery (>= 40 ms + 1 frame) would give.
        val rnd = Random(3)
        val a = ReliableStream(); val b = ReliableStream()
        val sentAt = HashMap<Int, Long>(); val delays = ArrayList<Long>(); var got = 0
        val inFlight = ArrayList<Pair<Long, Pair<Int, List<Pair<Int, ByteArray>>>>>()
        val acks = ArrayList<Pair<Long, Int>>()
        var now = 0L; var n = 0
        while (now < 60_000 * ms) {
            now += ms
            if (now % (16 * ms) == 0L) { sentAt[n] = now; a.queue(ByteBuffer.allocate(4).putInt(n++).array()) }
            a.startRound()
            while (a.hasDue(now)) {
                val p = packet(a, now); if (p.second.isEmpty()) break
                if (rnd.nextInt(10) != 0) inFlight.add(now + 20 * ms to p)
            }
            for (x in inFlight.filter { it.first <= now }) {
                inFlight.remove(x)
                for ((seq, m) in x.second.second) b.receive(seq, m) { val k = ByteBuffer.wrap(it).int; got++; delays.add(now - sentAt[k]!!) }
                if (rnd.nextInt(10) != 0) acks.add(now + 20 * ms to b.expected.toInt())
            }
            for (x in acks.filter { it.first <= now }) { acks.remove(x); a.onAck(x.second, now) }
        }
        assertTrue("delivered $got of $n", got >= n - 5)
        delays.sort()
        val p99 = delays[delays.size * 99 / 100]
        // RTO-only recovery would put every lost message at >= 20 + ~50 ms; here 99% stay within 3 frames.
        assertTrue("p99 ${p99 / ms} ms", p99 <= 20 * ms + 16 * ms * 3)
        assertTrue("max ${delays.last() / ms} ms", delays.last() <= 400 * ms)
    }
}
