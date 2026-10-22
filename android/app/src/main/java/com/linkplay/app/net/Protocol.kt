package com.linkplay.app.net

/*
 * The room protocol spoken over a Pipe between HostRoom and GuestSession. Each
 * message's first byte says what it is:
 *   0x01 control, UTF-8 JSON object (join / leave / welcome / roster / error / closed)
 *   0x02 LINK_DOWN host -> guest: u8 kind (1 handshake, 2 entry, 3 left), u8 player, payload (2 / 64 / 0 bytes)
 *   0x03 LINK_UP   guest -> host: u8 kind (1 handshake, 2 command), u16le words[1 | 8]
 * Control messages carry the same fields as server/relay.js, so the room logic
 * stays a straight port of the relay's.
 */

/** A parsed control message. */
sealed class Control {
    data class Join(val name: String, val rom: RomId?, val proto: Int) : Control()
    object Leave : Control()
    data class Welcome(val room: String, val linkId: Int, val players: List<RoomPlayer>) : Control()
    data class Roster(val linkId: Int, val players: List<RoomPlayer>) : Control()
    data class Error(val code: String, val message: String) : Control()
    data class Closed(val reason: String) : Control()
}

object Protocol {
    const val CONTROL: Byte = 1
    const val LINK_DOWN: Byte = 2
    const val LINK_UP: Byte = 3
    /** Room protocol version; a host refuses guests speaking another one. */
    const val VERSION = 1

    const val KIND_HANDSHAKE = 1
    const val KIND_COMMAND = 2
    const val KIND_ENTRY = 2
    const val KIND_LEFT = 3
    const val MAX_PLAYERS = 4
    const val CMD_WORDS = 8
    const val ENTRY_BYTES = MAX_PLAYERS * CMD_WORDS * 2

    const val MAX_NAME = 20 // same limits as relay.js
    const val MAX_ROOM_NAME = 32

    fun encode(c: Control): ByteArray {
        val obj: Map<String, Any?> = when (c) {
            is Control.Join -> mapOf(
                "t" to "join", "name" to c.name, "proto" to c.proto,
                "rom" to c.rom?.let { mapOf("sha1" to it.sha1, "title" to it.title, "code" to it.code, "size" to it.size) },
            )
            Control.Leave -> mapOf("t" to "leave")
            is Control.Welcome -> mapOf("t" to "welcome", "room" to c.room, "linkId" to c.linkId, "players" to players(c.players))
            is Control.Roster -> mapOf("t" to "roster", "linkId" to c.linkId, "players" to players(c.players))
            is Control.Error -> mapOf("t" to "error", "code" to c.code, "message" to c.message)
            is Control.Closed -> mapOf("t" to "closed", "reason" to c.reason)
        }
        return byteArrayOf(CONTROL) + Json.write(obj).toByteArray(Charsets.UTF_8)
    }

    /** Null for anything that isn't a well-formed control message we understand (ignored, like the relay does). */
    fun decodeControl(message: ByteArray): Control? {
        if (message.isEmpty() || message[0] != CONTROL) return null
        val obj = Json.parse(String(message, 1, message.size - 1, Charsets.UTF_8)) as? Map<*, *> ?: return null
        return when (obj["t"]) {
            "join" -> {
                val rom = (obj["rom"] as? Map<*, *>)?.let { r ->
                    val sha1 = (r["sha1"] as? String)?.lowercase() ?: return@let null
                    RomId(sha1, clean(r["title"], 32), clean(r["code"], 8), (r["size"] as? Number)?.toLong() ?: 0)
                }
                Control.Join(cleanName(obj["name"]), rom, (obj["proto"] as? Number)?.toInt() ?: 0)
            }
            "leave" -> Control.Leave
            "welcome" -> Control.Welcome(clean(obj["room"], MAX_ROOM_NAME), int(obj["linkId"]) ?: return null, players(obj["players"]) ?: return null)
            "roster" -> Control.Roster(int(obj["linkId"]) ?: return null, players(obj["players"]) ?: return null)
            "error" -> Control.Error(clean(obj["code"], 32), clean(obj["message"], 300).ifEmpty { "The host refused the connection." })
            "closed" -> Control.Closed(clean(obj["reason"], 300).ifEmpty { "Room closed." })
            else -> null
        }
    }

    /** LINK_UP from a Native.netPoll payload (u8 kind, u8 0, u16le words[1 | 8]); null if malformed. */
    fun linkUp(payload: ByteArray): ByteArray? {
        val words = wordCount(payload, 0, 2) ?: return null
        return byteArrayOf(LINK_UP, payload[0]) + payload.copyOfRange(2, 2 + words * 2)
    }

    fun linkDown(frame: ByteArray): ByteArray = byteArrayOf(LINK_DOWN) + frame

    /** A link frame for Native.netPush: u8 kind, u8 player, payload of exactly 2 / 64 / 0 bytes. */
    fun validFrame(f: ByteArray): Boolean {
        if (f.size < 2 || f[1].toInt() !in 0 until MAX_PLAYERS) return false
        return f.size == 2 + when (f[0].toInt()) {
            KIND_HANDSHAKE -> 2
            KIND_ENTRY -> ENTRY_BYTES
            KIND_LEFT -> 0
            else -> return false
        }
    }

    /** Words in a GBA -> link message whose kind byte is at [kindAt] and words start at [wordsAt]; null if unknown or short. */
    internal fun wordCount(msg: ByteArray, kindAt: Int, wordsAt: Int): Int? {
        if (msg.size <= kindAt) return null
        val n = when (msg[kindAt].toInt()) {
            KIND_HANDSHAKE -> 1
            KIND_COMMAND -> CMD_WORDS
            else -> return null
        }
        return if (msg.size >= wordsAt + n * 2) n else null
    }

    internal fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    /** Like relay.js str(): trimmed, at most [max] chars, no control characters, never splitting a surrogate pair. */
    fun clean(v: Any?, max: Int): String {
        val s = (v as? String ?: return "").filter { !it.isISOControl() && it != ' ' && it != ' ' }.trim()
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end).trim()
    }

    fun cleanName(v: Any?) = clean(v, MAX_NAME).ifEmpty { "Player" }

    private fun int(v: Any?): Int? = (v as? Number)?.toInt()

    private fun players(list: List<RoomPlayer>) = list.map { mapOf("linkId" to it.linkId, "name" to it.name, "connected" to it.connected) }

    private fun players(v: Any?): List<RoomPlayer>? {
        val list = v as? List<*> ?: return null
        return list.take(MAX_PLAYERS).map {
            val p = it as? Map<*, *> ?: return null
            RoomPlayer(int(p["linkId"]) ?: return null, cleanName(p["name"]), p["connected"] as? Boolean ?: true)
        }
    }
}

/**
 * Just enough JSON for the control messages (org.json is Android-only and this
 * package must run on a plain JVM). Objects become LinkedHashMap, arrays List,
 * numbers Long or Double. parse() returns null for anything malformed.
 */
internal object Json {
    private const val MAX_DEPTH = 16

    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Double, is Float -> {
                val d = (v as Number).toDouble()
                sb.append(if (d.isFinite()) d.toString() else "null")
            }
            is Number -> sb.append(v.toLong())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.forEachIndexed { i, value -> if (i > 0) sb.append(','); write(sb, value) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? = try {
        Parser(text).run {
            val v = value(0)
            ws()
            if (i != s.length) null else v
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun fail(): Nothing = throw IllegalArgumentException("bad JSON at $i")

        fun expect(c: Char) {
            if (s[i] != c) fail()
            i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail()
            ws()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') return map.also { i++ }
            while (true) {
                ws()
                val k = string()
                ws()
                expect(':')
                map[k] = value(depth + 1)
                ws()
                if (s[i] != ',') break
                i++
            }
            expect('}')
            return map
        }

        fun arr(depth: Int): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            ws()
            if (s[i] == ']') return list.also { i++ }
            while (true) {
                list.add(value(depth + 1))
                ws()
                if (s[i] != ',') break
                i++
            }
            expect(']')
            return list
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        fun number(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") fail()
            return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail()
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i, i + 4)
                            if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) fail()
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> fail()
                    }
                    c < ' ' -> fail()
                    else -> sb.append(c)
                }
            }
        }
    }
}
