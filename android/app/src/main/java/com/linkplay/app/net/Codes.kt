package com.linkplay.app.net

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Invite and reply codes: how two phones find each other with no server.
 * The host shares an invite (`LP1I-…`); a guest whose packets can't get through
 * the host's firewall sends back a reply (`LP1R-…`) so the host can open a hole
 * from its side. Both are short binary records in unpadded base64url.
 *
 * Invite: u8 version, u64 roomId, 16 B secret, 8 B ROM SHA-1 prefix, u8 maxPlayers,
 *         host name (u8 len + UTF-8 <= 20 B), room name (u8 len + <= 32 B),
 *         u8 n <= 8 x [u8 kind][u8 family 4|6][addr 4|16][u16 port], CRC32.
 * Reply:  u8 version, u64 roomId, u64 connId, guest name, candidates,
 *         first 8 B of HMAC-SHA256(secret, everything before), so the host only
 *         probes for someone who really had the invite.
 * Integers are big-endian.
 */

/** What [Codes.find] made of some pasted text. */
sealed interface CodeResult

/** Everything a guest needs to reach a room. */
class Invite(
    val roomId: Long,
    val secret: ByteArray,
    /** First 8 bytes of the ROM's SHA-1, so a guest with another ROM is told before connecting. */
    val romSha1Prefix: ByteArray,
    val maxPlayers: Int,
    val hostName: String,
    val roomName: String,
    val candidates: List<Candidate>,
) : CodeResult {
    val key get() = RoomKey(roomId, secret)

    fun romMatches(sha1Hex: String) = Codes.sha1Prefix(sha1Hex)?.contentEquals(romSha1Prefix) == true

    fun encode() = Codes.encode(this)
}

/** A guest's answer, telling the host where to send probes. Check [verify] before trusting it. */
class Reply internal constructor(
    val roomId: Long,
    val connId: Long,
    val guestName: String,
    val candidates: List<Candidate>,
    private val signed: ByteArray,
    private val mac: ByteArray,
) : CodeResult {
    /** True only if whoever made this reply had the room's invite. */
    fun verify(key: RoomKey) = key.roomId == roomId && MessageDigest.isEqual(Codes.mac(key.secret, signed), mac)
}

data class CodeError(val message: String) : CodeResult

object Codes {
    const val VERSION = 1
    const val MAX_HOST_NAME = 20 // UTF-8 bytes
    const val MAX_ROOM_NAME = 32
    const val MAX_CANDIDATES = 8
    private const val MAC_BYTES = 8
    private const val MAX_CHARS = 400 // longest possible invite is ~340 base64 chars

    private val random = SecureRandom()

    // Messengers wrap long words, add text around a pasted code and slip in non-breaking or
    // zero-width characters. The prefix skips exactly what collect() skips in the body:
    // Char.isWhitespace() is Java's isWhitespace or isSpaceChar (\p{Z}); Java's \s is ASCII only.
    private const val INVISIBLE = "\u200B\u200C\u200D\u2060\uFEFF\u00AD" // zero-width (non)joiners, word joiner, BOM, soft hyphen
    private const val GAP = "[\\p{javaWhitespace}\\p{Z}$INVISIBLE]*"
    private val PREFIX = Regex("L${GAP}P$GAP(\\d{1,3})$GAP([IR])$GAP-", RegexOption.IGNORE_CASE)

    private const val NEWER = "This code was made by a newer version of Link Play. Update the app on this phone."
    private const val BAD_INVITE = "This invite code is damaged or incomplete. Copy the whole message again."
    private const val BAD_REPLY = "This reply code is damaged or incomplete. Ask for it again and paste the whole message."

    /** A fresh room: random id and secret. Names are cut to what fits in the code, so everyone sees the same. */
    fun newInvite(hostName: String, roomName: String, rom: RomId, maxPlayers: Int, candidates: List<Candidate>): Invite {
        val secret = ByteArray(16).also(random::nextBytes)
        return Invite(random.nextLong(), secret, sha1Prefix(rom.sha1) ?: ByteArray(8), maxPlayers.coerceIn(2, 4),
            fit(hostName, MAX_HOST_NAME), fit(roomName, MAX_ROOM_NAME), usable(candidates))
    }

    /** A guest's connection id (also the transport's connId); never 0. */
    fun newConnId(): Long {
        while (true) random.nextLong().let { if (it != 0L) return it }
    }

    fun encode(invite: Invite): String {
        val w = Writer()
        w.u8(VERSION)
        w.u64(invite.roomId)
        w.bytes(invite.secret)
        w.bytes(invite.romSha1Prefix.copyOf(8))
        w.u8(invite.maxPlayers)
        w.str(invite.hostName, MAX_HOST_NAME)
        w.str(invite.roomName, MAX_ROOM_NAME)
        w.candidates(invite.candidates)
        val body = w.toByteArray()
        val crc = CRC32().apply { update(body) }.value
        w.u32(crc)
        return "LP${VERSION}I-" + b64(w.toByteArray())
    }

    /** The reply code a guest sends back to the host of the room keyed by [key] (from the invite). */
    fun reply(key: RoomKey, connId: Long, guestName: String, candidates: List<Candidate>): String {
        val w = Writer()
        w.u8(VERSION)
        w.u64(key.roomId)
        w.u64(connId)
        w.str(guestName, MAX_HOST_NAME)
        w.candidates(candidates)
        val body = w.toByteArray()
        return "LP${VERSION}R-" + b64(body + mac(key.secret, body))
    }

    /**
     * Finds a code anywhere in [text] (a whole pasted chat, even several messages, is fine).
     * The caller's kind wins: the host passes its [hostKey] and wants a reply made from that
     * room's invite (its own invite is often quoted above it); without a key the caller is a
     * guest and wants an invite (an old reply may sit above it). The first valid code of that
     * kind is returned, else the first one's problem, else the first valid code of the other
     * kind (so the UI can say what was pasted), else the first problem found.
     *
     * A code that got percent-encoded on its way (`LP1I%2D…`, some apps do that to shared text) is
     * found too: then the text is looked at again, decoded.
     */
    fun find(text: String, hostKey: RoomKey? = null): CodeResult {
        val plain = scan(text, hostKey)
        if (plain.rank == FOUND || '%' !in text) return plain.result
        val decoded = scan(percentDecode(text), hostKey)
        return if (decoded.rank > plain.rank) decoded.result else plain.result
    }

    // ---------------------------------------------------------------- finding

    /** What one look through some text found; a higher [rank] is closer to what the caller wants. */
    private class Scan(val result: CodeResult, val rank: Int)

    private const val NOTHING = 0 // no code at all
    private const val OTHER = 1 // only codes of the other kind
    private const val DAMAGED = 2 // a code of the wanted kind, but damaged
    private const val FOUND = 3 // a valid code of the wanted kind

    private fun scan(text: String, hostKey: RoomKey?): Scan {
        val wantInvite = hostKey == null
        var problem: CodeError? = null // first damaged code of the wanted kind
        var other: CodeResult? = null // first code of the other kind, valid or not
        for (m in PREFIX.findAll(text)) {
            val version = m.groupValues[1].toInt()
            val isInvite = m.groupValues[2].equals("I", ignoreCase = true)
            val r = decode(version, isInvite, collect(text, m.range.last + 1), hostKey)
            when {
                isInvite != wantInvite -> if (other == null || (other is CodeError && r !is CodeError)) other = r
                r !is CodeError -> return Scan(r, FOUND)
                problem == null -> problem = r
            }
        }
        return when {
            problem != null -> Scan(problem, DAMAGED)
            other != null -> Scan(other, OTHER)
            else -> Scan(CodeError("No Link Play code found. Paste the whole message with the LP1I-… or LP1R-… code."), NOTHING)
        }
    }

    /** [s] with every `%XX` decoded (runs of them as UTF-8); a `%` without two hex digits stays as it is. */
    private fun percentDecode(s: String): String {
        val out = StringBuilder(s.length)
        val run = ByteArrayOutputStream()
        fun flush() {
            if (run.size() == 0) return
            out.append(String(run.toByteArray(), Charsets.UTF_8))
            run.reset()
        }
        var i = 0
        while (i < s.length) {
            val hi = if (s[i] == '%' && i + 2 < s.length) hexDigit(s[i + 1]) else -1
            val lo = if (hi >= 0) hexDigit(s[i + 2]) else -1
            if (lo >= 0) {
                run.write(hi * 16 + lo)
                i += 3
            } else {
                flush()
                out.append(s[i++])
            }
        }
        flush()
        return out.toString()
    }

    private fun hexDigit(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ---------------------------------------------------------------- decoding

    /** The code's characters after the prefix, skipping whitespace; the record's own lengths say where it ends. */
    private fun collect(text: String, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < text.length && sb.length < MAX_CHARS) {
            val c = text[i++]
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> sb.append(c)
                c.isWhitespace() || c in INVISIBLE -> {}
                else -> break
            }
        }
        return sb.toString()
    }

    private fun decode(version: Int, isInvite: Boolean, chars: String, hostKey: RoomKey?): CodeResult {
        if (version > VERSION) return CodeError(NEWER)
        // A future record format comes with a new text prefix, so a v1 prefix
        // with another version byte is just damage.
        return try {
            val bytes = unb64(chars)
            if (isInvite) parseInvite(bytes) else parseReply(bytes, hostKey)
        } catch (e: Corrupt) {
            CodeError(if (isInvite) BAD_INVITE else BAD_REPLY)
        }
    }

    private fun parseInvite(bytes: ByteArray): Invite {
        val r = Reader(bytes)
        if (r.u8() != VERSION) throw Corrupt()
        val roomId = r.u64()
        val secret = r.bytes(16)
        val rom = r.bytes(8)
        val maxPlayers = r.u8()
        val host = r.str(MAX_HOST_NAME)
        val room = r.str(MAX_ROOM_NAME)
        val candidates = r.candidates()
        val end = r.pos
        val crc = r.u32()
        if (maxPlayers !in 2..4 || crc != CRC32().apply { update(bytes, 0, end) }.value) throw Corrupt()
        return Invite(roomId, secret, rom, maxPlayers, host, room, candidates)
    }

    private fun parseReply(bytes: ByteArray, hostKey: RoomKey?): CodeResult {
        val r = Reader(bytes)
        if (r.u8() != VERSION) throw Corrupt()
        val roomId = r.u64()
        val connId = r.u64()
        val name = r.str(MAX_HOST_NAME)
        val candidates = r.candidates()
        val end = r.pos
        val reply = Reply(roomId, connId, name, candidates, bytes.copyOf(end), r.bytes(MAC_BYTES))
        if (hostKey != null) {
            if (roomId != hostKey.roomId) return CodeError("This reply code is for a different room (maybe an older invite).")
            if (!reply.verify(hostKey)) return CodeError("This reply code doesn't match this room's invite. Ask for it again.")
        }
        return reply
    }

    // ---------------------------------------------------------------- helpers

    internal fun mac(secret: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(data)
        }.copyOf(MAC_BYTES)

    internal fun sha1Prefix(hex: String): ByteArray? {
        if (hex.length < 16 || !hex.substring(0, 16).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** [s] cut to at most [maxBytes] of UTF-8 without splitting a character. */
    fun fit(s: String, maxBytes: Int) = String(utf8Prefix(s, maxBytes), Charsets.UTF_8)

    private fun utf8Prefix(s: String, maxBytes: Int): ByteArray {
        val clean = Protocol.clean(s, 4 * maxBytes)
        var end = 0
        var bytes = 0
        while (end < clean.length) {
            val cp = clean.codePointAt(end)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            end += Character.charCount(cp)
        }
        return clean.substring(0, end).trim().toByteArray(Charsets.UTF_8)
    }

    private fun usable(list: List<Candidate>) = list.filter { it.address.address != null && it.address.port in 1..65535 }
        .distinct().take(MAX_CANDIDATES)

    private fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Lenient base64url: decodes every whole byte and ignores leftover bits (the code may be followed by words). */
    private fun unb64(s: String): ByteArray {
        val out = ByteArray(s.length * 6 / 8)
        var acc = 0
        var bits = 0
        var n = 0
        for (c in s) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> continue
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = (acc shr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out.copyOf(n)
    }

    private class Corrupt : Exception()

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun u8(): Int {
            if (pos >= b.size) throw Corrupt()
            return b[pos++].toInt() and 0xff
        }

        fun u16() = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun u64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun bytes(n: Int): ByteArray {
            if (pos + n > b.size) throw Corrupt()
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun str(maxBytes: Int): String {
            val n = u8()
            if (n > maxBytes) throw Corrupt()
            return Protocol.clean(String(bytes(n), Charsets.UTF_8), maxBytes)
        }

        fun candidates(): List<Candidate> {
            val n = u8()
            if (n > MAX_CANDIDATES) throw Corrupt()
            return (0 until n).mapNotNull {
                val kind = u8()
                val addr = when (u8()) {
                    4 -> bytes(4)
                    6 -> bytes(16)
                    else -> throw Corrupt()
                }
                val port = u16()
                // An unknown kind from a newer app is skipped, not fatal.
                val k = Candidate.Kind.of(kind)
                if (k == null || port == 0) null else Candidate(InetSocketAddress(InetAddress.getByAddress(addr), port), k)
            }
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = out.write(v and 0xff)

        fun u16(v: Int) {
            u8(v ushr 8)
            u8(v)
        }

        fun u32(v: Long) {
            u16((v ushr 16).toInt())
            u16(v.toInt())
        }

        fun u64(v: Long) {
            for (shift in 56 downTo 0 step 8) u8((v ushr shift).toInt())
        }

        fun bytes(b: ByteArray) = out.write(b)

        fun str(s: String, maxBytes: Int) {
            val b = utf8Prefix(s, maxBytes)
            u8(b.size)
            bytes(b)
        }

        fun candidates(list: List<Candidate>) {
            val ok = usable(list)
            u8(ok.size)
            for (c in ok) {
                val a = c.address.address.address // 4 or 16 bytes
                u8(c.kind.code)
                u8(if (a.size == 4) 4 else 6)
                bytes(a)
                u16(c.address.port)
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
