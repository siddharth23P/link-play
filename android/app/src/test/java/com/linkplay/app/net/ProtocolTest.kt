package com.linkplay.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val players = listOf(RoomPlayer(0, "Amy", true), RoomPlayer(1, "Bén \"B\" \\o/", true), RoomPlayer(2, "😀", false))

    @Test fun controlMessagesRoundTrip() {
        val all = listOf(
            Control.Join("Ash", TEST_ROM, 1),
            Control.Leave,
            Control.Welcome("Amy's room", 1, players),
            Control.Roster(2, players),
            Control.Error("rom_mismatch", "This room plays \"X\".\nNope."),
            Control.Closed("Room closed (host left)."),
        )
        for (c in all) {
            val msg = Protocol.encode(c)
            assertEquals(Protocol.CONTROL, msg[0])
            assertTrue("$c is ${msg.size} bytes", msg.size <= Pipe.MAX_MESSAGE)
            val back = Protocol.decodeControl(msg)
            // Error messages lose control characters (newline) on the way in, like every string.
            val expected = if (c is Control.Error) c.copy(message = "This room plays \"X\".Nope.") else c
            assertEquals(expected, back)
        }
    }

    @Test fun wireJsonMatchesTheSpec() {
        val json = String(Protocol.encode(Control.Join("Ash", TEST_ROM, 1)).let { it.copyOfRange(1, it.size) }, Charsets.UTF_8)
        assertEquals("""{"t":"join","name":"Ash","proto":1,"rom":{"sha1":"${TEST_ROM.sha1}","title":"POKEMON EMER","code":"BPEE","size":16777216}}""", json)
        val parsed = Json.parse(json) as Map<*, *>
        assertEquals("join", parsed["t"])
        assertEquals(16777216L, (parsed["rom"] as Map<*, *>)["size"])
    }

    @Test fun jsonParserHandlesTheUsualAndRejectsGarbage() {
        val v = Json.parse(""" { "a" : [1, -2.5, 3e2, true, false, null, "x\u00e9\n\"\\\/"], "b": {}, "c": [] } """) as Map<*, *>
        assertEquals(listOf(1L, -2.5, 300.0, true, false, null, "xé\n\"\\/"), v["a"])
        assertEquals(emptyMap<String, Any?>(), v["b"])
        assertEquals(emptyList<Any?>(), v["c"])
        assertEquals("😀", Json.parse("\"\\ud83d\\ude00\""))
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\" 1}", "tru", "\"abc", "{} x", "[" .repeat(100) + "]".repeat(100), "\"\\x\"", "01a", "-")) {
            assertNull("should reject: $bad", Json.parse(bad))
        }
        // Strings with control characters and line separators survive a round trip.
        val s = "a\u0001b\u2028c\td"
        assertEquals(s, Json.parse(Json.write(s)))
    }

    @Test fun malformedControlIsIgnored() {
        assertNull(Protocol.decodeControl(byteArrayOf(1) + "not json".toByteArray()))
        assertNull(Protocol.decodeControl(byteArrayOf(1) + "[1,2]".toByteArray()))
        assertNull(Protocol.decodeControl(byteArrayOf(1) + """{"t":"nope"}""".toByteArray()))
        assertNull(Protocol.decodeControl(byteArrayOf(1) + """{"t":"welcome","room":"x"}""".toByteArray()))
        assertNull(Protocol.decodeControl(byteArrayOf(2, 1, 0, 0, 0)))
        assertNull(Protocol.decodeControl(byteArrayOf()))
        // A join without a ROM still parses (the host answers rom_mismatch), names are cleaned.
        val j = Protocol.decodeControl(byteArrayOf(1) + """{"t":"join","name":"  A\u0000very long name indeed, really  "}""".toByteArray())
        assertEquals(Control.Join("Avery long name inde", null, 0), j)
        val anon = Protocol.decodeControl(byteArrayOf(1) + """{"t":"join","name":42,"proto":1}""".toByteArray())
        assertEquals(Control.Join("Player", null, 1), anon)
    }

    @Test fun linkUpAndFrames() {
        val hs = Protocol.linkUp(gbaHandshake(0x8FFF))!!
        assertArrayEquals(byteArrayOf(3, 1, 0xFF.toByte(), 0x8F.toByte()), hs)
        val words = intArrayOf(0xBEBE, 2, 3, 4, 5, 6, 7, 0xFFFF)
        val cmd = Protocol.linkUp(gbaCommand(words))!!
        assertEquals(18, cmd.size)
        assertEquals(3.toByte(), cmd[0])
        assertEquals(2.toByte(), cmd[1])
        for (i in 0 until 8) assertEquals(words[i], Protocol.u16(cmd, 2 + i * 2))
        assertNull(Protocol.linkUp(byteArrayOf(2, 0, 1, 2)))
        assertNull(Protocol.linkUp(byteArrayOf(9, 0, 1, 2)))
        assertNull(Protocol.linkUp(byteArrayOf()))

        assertTrue(Protocol.validFrame(byteArrayOf(1, 3, 0, 0)))
        assertTrue(Protocol.validFrame(ByteArray(66).also { it[0] = 2 }))
        assertTrue(Protocol.validFrame(byteArrayOf(3, 2)))
        assertFalse(Protocol.validFrame(byteArrayOf(3, 4)))
        assertFalse(Protocol.validFrame(byteArrayOf(2, 0, 1)))
        assertFalse(Protocol.validFrame(byteArrayOf(7, 0)))
        assertArrayEquals(byteArrayOf(2, 3, 2), Protocol.linkDown(byteArrayOf(3, 2)))
    }

    @Test fun cleanNeverSplitsSurrogates() {
        val s = "a".repeat(19) + "😀"
        assertEquals("a".repeat(19), Protocol.clean(s, 20))
        assertEquals(s, Protocol.clean(s, 21))
        assertEquals("Player", Protocol.cleanName("   "))
    }
}
