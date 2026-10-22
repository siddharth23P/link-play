package com.linkplay.app.net

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals and opens UdpTransport packets:
 *   'L', 0x01, 12-byte random nonce, AES-128-GCM(plaintext) || 16-byte tag
 * with AAD = the two header bytes + roomId (8 bytes big-endian), so a packet only opens with this
 * room's key and cannot be replayed into another room.
 * Sealing (worker thread) and opening (receive thread) each have their own Cipher; both are
 * synchronized anyway because a Cipher is not thread-safe.
 */
internal class PacketCrypto(key: RoomKey) {
    private val aesKey = SecretKeySpec(deriveKey(key), "AES")
    private val aad = ByteBuffer.allocate(10).put(MAGIC).put(VERSION).putLong(key.roomId).array()
    private val random = SecureRandom()
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(NONCE)

    /** The packet carrying the first [len] bytes of [plain]. */
    fun seal(plain: ByteArray, len: Int): ByteArray {
        val out = ByteArray(OVERHEAD + len)
        out[0] = MAGIC
        out[1] = VERSION
        synchronized(enc) {
            random.nextBytes(nonce)
            System.arraycopy(nonce, 0, out, 2, NONCE)
            enc.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(TAG * 8, nonce))
            enc.updateAAD(aad)
            enc.doFinal(plain, 0, len, out, HEADER)
        }
        return out
    }

    /** The plaintext, or null unless this is an authentic packet of this room. */
    fun open(packet: ByteArray, len: Int): ByteArray? {
        if (len < OVERHEAD || packet[0] != MAGIC || packet[1] != VERSION) return null
        return try {
            synchronized(dec) {
                dec.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(TAG * 8, packet, 2, NONCE))
                dec.updateAAD(aad)
                dec.doFinal(packet, HEADER, len - HEADER)
            }
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    companion object {
        const val MAGIC: Byte = 0x4C // 'L'
        const val VERSION: Byte = 1
        const val NONCE = 12
        const val HEADER = 2 + NONCE
        const val TAG = 16
        const val OVERHEAD = HEADER + TAG

        /** First 16 bytes of HMAC-SHA256(secret, "linkplay-p2p-v1" || roomId big-endian). */
        fun deriveKey(key: RoomKey): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.secret, "HmacSHA256"))
            mac.update("linkplay-p2p-v1".toByteArray(Charsets.US_ASCII))
            mac.update(ByteBuffer.allocate(8).putLong(key.roomId).array())
            return mac.doFinal().copyOf(16)
        }
    }
}
