package com.linkplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Differential check of Sequencer (+ HostRoom's LEFT-then-renumber step) against a line-by-line
 * transliteration of server/relay.js Room.onCommand / pump / remove, which keeps time in float
 * milliseconds and members in a Map like the original. (During the review the same random scripts
 * were also run through the real relay.js Room class under node: identical output for 40 seeds.)
 */
class SequencerReviewTest {
    /** relay.js Room, minus sockets: frames go to [out] as hex "kind player payload". */
    private class RelayRoom(var nowMs: Double) {
        val members = LinkedHashMap<Int, String>()
        var fifo = Array(4) { ArrayList<IntArray>() }
        var nextTick = nowMs
        val out = ArrayList<String>()

        fun broadcast(kind: Int, player: Int, payload: ByteArray = ByteArray(0)) {
            out.add((byteArrayOf(kind.toByte(), player.toByte()) + payload).hex())
        }

        fun onCommand(linkId: Int, words: IntArray) {
            val q = fifo[linkId]
            if (q.size >= 2 && Sequencer.stateReplaceable(q[q.size - 1], words)) q[q.size - 1] = words
            else if (q.size < 600) q.add(words)
        }

        // Adapted: relay.js ticks on a fixed grid; the phone's sequencer emits as soon as a command
        // is queued and a frame has passed since the last entry (see Sequencer.pump).
        fun pump() {
            val now = nowMs
            if (now < nextTick || fifo.all { it.isEmpty() }) return
            nextTick = if (now - nextTick < FRAME_MS) maxOf(nextTick + FRAME_MS, now + FRAME_MS / 2) else now + FRAME_MS
            val entry = ByteArray(64)
            for (p in 0 until 4) {
                val cmd = if (fifo[p].isEmpty()) continue else fifo[p].removeAt(0)
                for (w in 0 until 8) {
                    entry[(p * 8 + w) * 2] = cmd[w].toByte()
                    entry[(p * 8 + w) * 2 + 1] = (cmd[w] shr 8).toByte()
                }
            }
            broadcast(2, 0, entry)
        }

        fun remove(gone: Int) {
            broadcast(3, gone)
            members.remove(gone)
            val renumbered = LinkedHashMap<Int, String>()
            val fifos = Array(4) { ArrayList<IntArray>() }
            members.entries.sortedBy { it.key }.forEachIndexed { i, (id, m) ->
                renumbered[i] = m
                fifos[i] = fifo[id]
            }
            members.clear()
            members.putAll(renumbered)
            fifo = fifos
        }

        fun freeLinkId() = (0 until 4).first { it !in members }

        companion object {
            // relay.js uses the exact 280896 / 16777216 s; FRAME_NS drops 0.3 ns of it (18 ppb), which
            // after thousands of frames can move a tick across a pump boundary. Same length here, so
            // the comparison is about logic, not that rounding.
            const val FRAME_MS = FRAME_NS / 1e6
        }
    }

    @Test fun matchesRelayJsRoomOnRandomScripts() {
        for (seed in 1..25) {
            val rnd = Random(seed)
            val relay = RelayRoom(0.0)
            relay.members[0] = "host"
            val seq = Sequencer(0)
            val ours = ArrayList<String>()
            var size = 1
            var now = 0L
            repeat(5000) {
                when (rnd.nextInt(100)) {
                    in 0..59 -> {
                        val p = rnd.nextInt(size)
                        val w = if (rnd.nextInt(3) != 0) {
                            intArrayOf(0xBEBE, rnd.nextInt(2), rnd.nextInt(65536), rnd.nextInt(65536), 0, rnd.nextInt(2), (rnd.nextInt(2) shl 8) or rnd.nextInt(256), 9)
                        } else IntArray(8) { rnd.nextInt(65536) }
                        relay.onCommand(p, w)
                        seq.onCommand(p, w.copyOf())
                    }
                    in 60..93 -> {
                        now += if (rnd.nextInt(60) == 0) rnd.nextLong(500_000_000, 3_000_000_000) else rnd.nextLong(1_000_000, 30_000_000)
                        relay.nowMs = now / 1e6
                        relay.pump()
                        seq.pump(now) { ours.add((byteArrayOf(2, 0) + it).hex()) }
                    }
                    in 94..96 -> if (size > 1) {
                        val gone = 1 + rnd.nextInt(size - 1)
                        relay.remove(gone)
                        ours.add(byteArrayOf(3, gone.toByte()).hex()) // HostRoom.remove: LEFT first...
                        seq.remove(gone) // ...then the queues move down with their owners
                        size--
                    }
                    else -> if (size < 4) {
                        relay.members[relay.freeLinkId()] = "g"
                        size++
                    }
                }
            }
            assertEquals("seed $seed", relay.out, ours)
        }
    }
}
