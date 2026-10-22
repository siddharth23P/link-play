package com.linkplay.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PacketCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Expected values computed independently with Python's hmac module. */
    @Test
    fun keyDerivationMatchesSpec() {
        assertEquals("1baca6a01c69503afb2835e86aaa0d8e", hex(PacketCrypto.deriveKey(RoomKey(0x0123456789ABCDEFL, ByteArray(16) { it.toByte() }))))
        assertEquals("4232c4d6caeb5cf4f97816f787ffa887", hex(PacketCrypto.deriveKey(RoomKey(-2L, ByteArray(16) { -1 }))))
    }

    @Test
    fun layoutAndAadAreTheSpecs() {
        val key = RoomKey(0x0123456789ABCDEFL, ByteArray(16) { it.toByte() })
        val plain = "hello, link cable".toByteArray()
        val p = PacketCrypto(key).seal(plain + ByteArray(5), plain.size) // only the first len bytes
        assertEquals(2 + 12 + plain.size + 16, p.size)
        assertEquals('L'.code.toByte(), p[0])
        assertEquals(1.toByte(), p[1])
        // Open it with plain javax.crypto from the spec's description alone.
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(PacketCrypto.deriveKey(key), "AES"), GCMParameterSpec(128, p, 2, 12))
        c.updateAAD(ByteBuffer.allocate(10).put(0x4C).put(1).putLong(key.roomId).array())
        assertArrayEquals(plain, c.doFinal(p, 14, p.size - 14))
    }

    @Test
    fun roundTripWithFreshNonces() {
        val key = UdpTestKit.newKey()
        val a = PacketCrypto(key)
        val b = PacketCrypto(key)
        val plain = ByteArray(1170) { it.toByte() }
        val p1 = a.seal(plain, plain.size)
        val p2 = a.seal(plain, plain.size)
        assertFalse(p1.contentEquals(p2))
        assertArrayEquals(plain, b.open(p1, p1.size))
        assertArrayEquals(plain, b.open(p2, p2.size))
        assertArrayEquals(ByteArray(0), b.open(a.seal(plain, 0), 30))
    }

    @Test
    fun rejectsEverythingElse() {
        val key = UdpTestKit.newKey()
        val p = PacketCrypto(key).seal(ByteArray(40) { 3 }, 40)
        assertNull(PacketCrypto(RoomKey(key.roomId, ByteArray(16))).open(p, p.size)) // other secret
        assertNull(PacketCrypto(RoomKey(key.roomId + 1, key.secret)).open(p, p.size)) // other room
        val me = PacketCrypto(key)
        for (len in 0 until p.size) assertNull("truncated to $len", me.open(p, len))
        for (bit in 0 until p.size * 8) {
            val bad = p.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl bit % 8)).toByte() }
            assertNull("bit $bit flipped", me.open(bad, bad.size))
        }
        val trailing = p + byteArrayOf(0)
        assertNull(me.open(trailing, trailing.size))
    }
}
