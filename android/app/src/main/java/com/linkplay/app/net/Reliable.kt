package com.linkplay.app.net

import java.nio.ByteBuffer

/**
 * The reliable, ordered part of a Pipe, without sockets or threads (UdpTransport's worker owns
 * it). Messages get sequence numbers from 1; every DATA carries "the next seq I expect" as a
 * cumulative ack plus the unacked messages, oldest first, that are new or whose last send is
 * older than the RTO. The receiver delivers in order and keeps a bounded reorder buffer.
 * Sequence numbers are u32 on the wire and unwrapped against local state, so they never wrap.
 */
internal class ReliableStream(private val reorderWindow: Int = REORDER_WINDOW) {
    private class Out(val seq: Long, val data: ByteArray) {
        var firstSent = 0L
        var lastSent = 0L
        var sends = 0
        var copies = 0 // proactive repeats (not resends: they say nothing about the RTT)
    }

    private val unacked = ArrayDeque<Out>()
    private var nextSeq = 1L
    private var cursor = 0
    private var oldestSend = UNKNOWN // min lastSent over sent unacked messages, cached
    private var roundRto = 0L
    var srttNs = -1L
        private set

    // Karn's backoff: while the RTO is below the real RTT every message gets resent before its ack
    // and no sample is ever clean, so each timeout of the oldest message doubles the RTO (still
    // clamped to 300 ms) until a clean sample arrives.
    private var backoff = 1
    private var backedOff = false

    private var recvNext = 1L
    private val early = HashMap<Long, ByteArray>()

    val backlog get() = unacked.size
    val allAcked get() = unacked.isEmpty()

    /** clamp(1.15 * srtt * backoff, 10 ms, 300 ms); 100 ms before the first sample. */
    val rtoNs: Long get() = ((if (srttNs < 0) INITIAL_RTO_NS else srttNs * 23 / 20) * backoff).coerceIn(MIN_RTO_NS, MAX_RTO_NS)

    fun queue(message: ByteArray) {
        unacked.addLast(Out(nextSeq++, message))
    }

    /** Messages that were never sent are always at the tail (sending goes oldest first). */
    fun hasUnsent() = unacked.lastOrNull()?.sends == 0

    /** When the earliest retransmission is due; Long.MAX_VALUE if nothing is in flight. */
    fun retransmitAt(): Long {
        if (oldestSend == UNKNOWN) {
            var m = Long.MAX_VALUE
            for (o in unacked) if (o.sends > 0 && o.lastSent < m) m = o.lastSent
            oldestSend = m
        }
        return if (oldestSend == Long.MAX_VALUE) Long.MAX_VALUE else oldestSend + rtoNs
    }

    fun hasDue(now: Long) = hasUnsent() || retransmitAt() <= now

    /** Starts a send round: the next [fill]s walk the backlog once, oldest first, with one RTO. */
    fun startRound() {
        cursor = 0
        roundRto = rtoNs
        backedOff = false
    }

    /**
     * Writes one DATA body (u32 ack, u8 count, count x [u32 seq][u16 len][bytes]) into [out] using at
     * most [room] bytes, taking the due messages after the round's cursor ([startRound] first).
     * Returns the count.
     */
    fun fill(out: ByteBuffer, now: Long, room: Int): Int {
        out.putInt(recvNext.toInt())
        val countAt = out.position()
        out.put(0)
        var left = room - 5
        var n = 0
        var i = cursor
        // A packet that carries something new or overdue also repeats messages sent once already
        // (not yet due), so one lost datagram is made good by the next packet instead of by the RTO.
        // Ack-only packets carry no repeats.
        val repeating = hasDue(now)
        var repeatBytes = 0
        while (i < unacked.size && n < 255) {
            val o = unacked[i]
            val due = o.sends == 0 || now - o.lastSent >= roundRto
            if (!due && repeating && o.copies < MAX_COPIES && o.sends == 1 &&
                repeatBytes + o.data.size + 6 <= MAX_REPEAT_BYTES && o.data.size + 6 <= left) {
                out.putInt(o.seq.toInt()).putShort(o.data.size.toShort()).put(o.data)
                o.copies++
                repeatBytes += o.data.size + 6
                left -= o.data.size + 6
                n++
            } else if (due) {
                if (o.data.size + 6 > left) break // oldest first: it leads the next packet
                if (i == 0 && o.sends > 0 && !backedOff) {
                    backoff = minOf(backoff * 2, MAX_BACKOFF)
                    backedOff = true
                }
                out.putInt(o.seq.toInt()).putShort(o.data.size.toShort()).put(o.data)
                if (o.sends == 0) o.firstSent = now
                o.sends++
                o.lastSent = now
                left -= o.data.size + 6
                n++
            }
            i++
        }
        cursor = i
        out.put(countAt, n.toByte())
        if (n > 0) oldestSend = UNKNOWN
        return n
    }

    /** A cumulative ack from the peer: everything below [ack32] arrived. */
    fun onAck(ack32: Int, now: Long) {
        val ack = nextSeq + (ack32 - nextSeq.toInt()).toLong()
        if (ack > nextSeq) return // acks something never sent: ignore
        var last: Out? = null
        var resent = false
        while (unacked.isNotEmpty() && unacked.first().seq < ack) {
            last = unacked.removeFirst()
            if (last.sends > 1) resent = true
        }
        if (last == null) return
        oldestSend = UNKNOWN
        // Karn: a range that needed a resend says nothing clean about the RTT (it also
        // includes the wait for the resend when the head of the line was lost).
        if (!resent && last.sends == 1) {
            // With a proactive copy in a later packet, the ack may be for the copy: no RTT sample then.
            if (last.copies == 0) {
                val sample = now - last.firstSent
                srttNs = if (srttNs < 0) sample else srttNs + (sample - srttNs) / 8
            }
            backoff = 1
        }
    }

    /** A message from the peer; [deliver] gets every message that is now in order. */
    fun receive(seq32: Int, data: ByteArray, deliver: (ByteArray) -> Unit) {
        val seq = recvNext + (seq32 - recvNext.toInt()).toLong()
        when {
            seq < recvNext -> {} // duplicate
            seq == recvNext -> {
                recvNext++
                deliver(data)
                while (true) {
                    val next = early.remove(recvNext) ?: break
                    recvNext++
                    deliver(next)
                }
            }
            seq - recvNext < reorderWindow -> early.putIfAbsent(seq, data)
            // else: too far ahead; the sender resends it once the gap is filled
        }
    }

    /** Next seq we expect from the peer (what our acks say). */
    val expected get() = recvNext

    companion object {
        const val REORDER_WINDOW = 4096
        const val INITIAL_RTO_NS = 100_000_000L
        const val MIN_RTO_NS = 25_000_000L
        const val MAX_RTO_NS = 300_000_000L
        private const val MAX_BACKOFF = 16
        private const val MAX_COPIES = 2
        private const val MAX_REPEAT_BYTES = 400
        private const val UNKNOWN = Long.MIN_VALUE
    }
}
