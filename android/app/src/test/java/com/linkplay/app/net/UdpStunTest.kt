package com.linkplay.app.net

import com.linkplay.app.net.UdpTestKit.V4
import com.linkplay.app.net.UdpTestKit.V6
import com.linkplay.app.net.UdpTestKit.newKey
import com.linkplay.app.net.UdpTestKit.opts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class UdpStunTest {
    /** RFC 5769 2.2 / 2.3 sample responses (MESSAGE-INTEGRITY and FINGERPRINT zeroed: we don't check them). */
    private val txId = bytes("b7e7a701bc34d686fa87dfae")

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun rfcResponse(xorAttr: String, length: Int) = bytes(
        "0101" + "%04x".format(length) + "2112a442" + "b7e7a701bc34d686fa87dfae" +
            "8022000b" + "7465737420766563746f7220" + xorAttr +
            "00080014" + "00".repeat(20) + "80280004" + "00000000",
    )

    @Test
    fun parsesRfc5769Ipv4() {
        val p = rfcResponse("00200008" + "0001a147" + "e112a643", 0x3c)
        assertTrue(Stun.isStun(p, p.size))
        val r = Stun.parseResponse(p, p.size)!!
        assertTrue(r.txId.contentEquals(txId))
        assertEquals(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), r.mapped)
    }

    @Test
    fun parsesRfc5769Ipv6() {
        val p = rfcResponse("00200014" + "0002a147" + "0113a9faa5d3f179bc25f4b5bed2b9d9", 0x48)
        val r = Stun.parseResponse(p, p.size)!!
        assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853), r.mapped)
    }

    @Test
    fun rejectsMalformed() {
        val p = rfcResponse("00200008" + "0001a147" + "e112a643", 0x3c)
        assertNull(Stun.parseResponse(p, 19))
        assertNull(Stun.parseResponse(p.copyOf().also { it[1] = 0x11 }, p.size)) // error response
        assertNull(Stun.parseResponse(p.copyOf().also { it[4] = 0 }, p.size)) // no magic cookie
        assertNull(Stun.parseResponse(p, p.size - 1)) // shorter than its length field
        val r = Stun.parseResponse(p.copyOf().also { it[41] = 9 }, p.size)!! // unknown address family
        assertNull(r.mapped)
        val ours = PacketCrypto(newKey()).seal(ByteArray(40), 40)
        assertTrue(!Stun.isStun(ours, ours.size))
    }

    /** A STUN server on loopback that answers binding requests with [mapped] (or the requester's own address). */
    private class FakeStun(bind: InetAddress, val mapped: InetSocketAddress? = null, val xor: Boolean = true, val corruptTxId: Boolean = false) : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(bind, 0))
        val address = InetSocketAddress(bind, socket.localPort)
        val requests = AtomicInteger()
        private val thread = Thread {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    p.setData(buf)
                    socket.receive(p)
                    val req = ByteBuffer.wrap(buf, 0, p.length)
                    if (p.length < 20 || req.short.toInt() != 1 || req.getInt(4) != 0x2112A442) continue
                    requests.incrementAndGet()
                    val tx = buf.copyOfRange(8, 20)
                    if (corruptTxId) tx[0] = (tx[0] + 1).toByte()
                    val answer = respond(tx, mapped ?: p.socketAddress as InetSocketAddress)
                    socket.send(DatagramPacket(answer, answer.size, p.socketAddress))
                }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true; start() }

        /** SOFTWARE (odd length, so padding is exercised) then the address attribute. */
        private fun respond(tx: ByteArray, a: InetSocketAddress): ByteArray {
            val ip = a.address.address
            val mask = ByteBuffer.allocate(16).putInt(0x2112A442).put(tx).array()
            val attr = ByteBuffer.allocate(4 + 4 + ip.size)
                .putShort((if (xor) 0x0020 else 0x0001).toShort()).putShort((4 + ip.size).toShort())
                .put(0).put((if (ip.size == 4) 1 else 2).toByte())
                .putShort((if (xor) a.port xor 0x2112 else a.port).toShort())
                .put(ByteArray(ip.size) { if (xor) (ip[it].toInt() xor mask[it].toInt()).toByte() else ip[it] })
            val software = byteArrayOf(0x80.toByte(), 0x22, 0, 5, 'f'.code.toByte(), 'a'.code.toByte(), 'k'.code.toByte(), 'e'.code.toByte(), '!'.code.toByte(), 0, 0, 0)
            val body = software + attr.array()
            return ByteBuffer.allocate(20 + body.size).putShort(0x0101).putShort(body.size.toShort()).putInt(0x2112A442).put(tx).put(body).array()
        }

        override fun close() {
            socket.close()
            thread.join(1000)
        }
    }

    @Test
    fun stunOverIpv4ReportsWhatTheServerSees() {
        val t = UdpTransport(newKey(), true, opts(V4))
        FakeStun(V4).use { server ->
            assertEquals(InetSocketAddress(V4, t.port), t.stun(server.address))
        }
        FakeStun(V4, mapped = InetSocketAddress(InetAddress.getByName("203.0.113.7"), 40000), xor = false).use { server ->
            assertEquals(InetSocketAddress(InetAddress.getByName("203.0.113.7"), 40000), t.stun(server.address))
        }
        t.close()
    }

    @Test
    fun stunOverIpv6DecodesXorAddress() {
        val t = UdpTransport(newKey(), false, opts(V6))
        val public = InetSocketAddress(InetAddress.getByName("2401:4900:1c2e:8a1f:aabb:ccdd:eeff:1"), 51234)
        FakeStun(V6, mapped = public).use { server ->
            assertEquals(public, t.stun(server.address))
        }
        FakeStun(V6).use { server ->
            assertEquals(InetSocketAddress(V6, t.port), t.stun(server.address))
        }
        t.close()
    }

    @Test
    fun unansweredOrMismatchedStunGivesNull() {
        val t = UdpTransport(newKey(), true, opts(V4))
        FakeStun(V4, corruptTxId = true).use { server ->
            val start = System.nanoTime()
            assertNull(t.stun(server.address, 800))
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("took $ms ms", ms in 700..1500)
            assertTrue("retried: ${server.requests.get()}", server.requests.get() >= 2)
        }
        t.close()
        FakeStun(V4).use { server -> assertNull(t.stun(server.address)) } // closed transport
    }

    @Test
    fun stunSharesTheSocketWithALivePipe() = UdpTestPair().use { t ->
        val (g, h) = t.connect()
        FakeStun(V4).use { server ->
            g.send("before".toByteArray())
            assertEquals(InetSocketAddress(V4, t.host.port), t.host.stun(server.address))
            assertNotNull(t.guest.stun(server.address))
            h.send("after".toByteArray())
            assertEquals("before", t.hostPeer.nextText())
            assertEquals("after", t.guestPeer.nextText())
        }
        assertEquals(0L, t.host.rejected.get())
    }
}
