package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import com.linkplay.app.net.UdpTestKit.cand
import com.linkplay.app.net.UdpTestKit.waitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** The reliable stream over a bad network: 20% loss, 80 +- 40 ms delay (so heavy reordering), 5% duplicates. */
class UdpStressTest {
    private val n = 10_000

    /** Message i: its index and a length / content that depend on i, now and then the largest allowed. */
    private fun message(i: Int, salt: Int): ByteArray {
        val len = if (i % 997 == 0) Pipe.MAX_MESSAGE else 4 + (i * 7919 + salt) % 61
        return ByteArray(len) { (i * 31 + it * 17 + salt).toByte() }.also { ByteBuffer.wrap(it).putInt(i) }
    }

    /** Checks that messages arrive exactly once and in order. */
    private inner class Inbox(private val salt: Int) {
        val count = AtomicInteger()
        @Volatile var error: String? = null

        fun accept(m: ByteArray) {
            val i = count.get()
            if (error == null && !m.contentEquals(message(i, salt))) {
                error = "message $i: got index ${if (m.size >= 4) ByteBuffer.wrap(m).int else -1}, ${m.size} bytes"
            }
            count.incrementAndGet()
        }
    }

    @Test
    fun tenThousandMessagesEachWayExactlyOnceInOrder() = UdpTestPair().use { t ->
        UdpTestProxy(InetSocketAddress(V4, t.host.port)).use { net ->
            net.loss = 0.2
            net.delayMs = 80
            net.jitterMs = 40
            net.duplicate = 0.05
            val atHost = Inbox(1)
            val atGuest = Inbox(2)
            t.hostPeer.hook = { _, m -> atHost.accept(m) }
            t.guestPeer.hook = { _, m -> atGuest.accept(m) }
            val start = System.nanoTime()
            val (g, h) = t.connect(listOf(cand(V4, net.address.port)), timeoutMs = 10_000)

            // First half paced like a game (a few messages per packet, so thousands of packets get lost,
            // duplicated and reordered), then as fast as it goes (full packets). Senders stay within ~800
            // messages of what the other side has, well under the 4000 backlog that would close the pipe.
            fun sender(pipe: Pipe, salt: Int, peer: Inbox) = Thread {
                for (i in 0 until n) {
                    if (i < n / 2 && i % 2 == 1) Thread.sleep(1)
                    while (i - peer.count.get() > 800 && !t.hostPeer.closed.isDone && !t.guestPeer.closed.isDone) Thread.sleep(1)
                    pipe.send(message(i, salt))
                }
            }.apply { start() }
            val senders = listOf(sender(g, 1, atHost), sender(h, 2, atGuest))
            waitUntil(90_000, "all messages (host has ${atHost.count}, guest has ${atGuest.count})") {
                (atHost.count.get() >= n && atGuest.count.get() >= n) || t.hostPeer.closed.isDone || t.guestPeer.closed.isDone
            }
            senders.forEach { it.join() }
            val secs = (System.nanoTime() - start) / 1e9
            assertFalse("host pipe closed: ${t.hostPeer.closed.getNow(null)}", t.hostPeer.closed.isDone)
            assertFalse("guest pipe closed: ${t.guestPeer.closed.getNow(null)}", t.guestPeer.closed.isDone)
            assertNull(atHost.error)
            assertNull(atGuest.error)
            Thread.sleep(500) // retransmissions and duplicates still in flight must not deliver anything again
            assertEquals(n, atHost.count.get())
            assertEquals(n, atGuest.count.get())
            println("lossy network: %d messages each way in %.1f s; proxy forwarded %d datagrams, dropped %d"
                .format(n, secs, net.forwarded.get(), net.dropped.get()))
        }
    }
}
