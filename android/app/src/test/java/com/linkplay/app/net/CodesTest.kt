package com.linkplay.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class CodesTest {
    private val v6 = Candidate(InetSocketAddress(InetAddress.getByName("2401:4900:1c2a:7f3e:a1b2:c3d4:e5f6:789a"), 50123), Candidate.Kind.IPV6)
    private val lan = Candidate(InetSocketAddress(InetAddress.getByName("192.168.1.23"), 50123), Candidate.Kind.LAN)
    private val mapped = Candidate(InetSocketAddress(InetAddress.getByName("49.36.10.200"), 61000), Candidate.Kind.MAPPED)

    private fun invite(host: String = "Amy", room: String = "Amy's room", cands: List<Candidate> = listOf(v6, lan, mapped)) =
        Codes.newInvite(host, room, TEST_ROM, 4, cands)

    private fun asInvite(r: CodeResult): Invite {
        if (r is CodeError) throw AssertionError("expected an invite, got: ${r.message}")
        return r as Invite
    }

    private fun asReply(r: CodeResult): Reply {
        if (r is CodeError) throw AssertionError("expected a reply, got: ${r.message}")
        return r as Reply
    }

    @Test fun inviteRoundTrip() {
        val inv = invite()
        val text = inv.encode()
        assertTrue(text, text.startsWith("LP1I-"))
        assertTrue("invite should be short, was ${text.length} chars: $text", text.length <= 160)
        val back = asInvite(Codes.find(text))
        assertEquals(inv.roomId, back.roomId)
        assertArrayEquals(inv.secret, back.secret)
        assertArrayEquals(inv.romSha1Prefix, back.romSha1Prefix)
        assertEquals(4, back.maxPlayers)
        assertEquals("Amy", back.hostName)
        assertEquals("Amy's room", back.roomName)
        assertEquals(listOf(v6, lan, mapped), back.candidates)
        assertTrue(back.romMatches(TEST_ROM.sha1))
        assertFalse(back.romMatches("f".repeat(40)))
        assertEquals(inv.key.roomId, back.key.roomId)
    }

    @Test fun findsCodeInsideAChatMessageWithLineBreaks() {
        val code = invite().encode()
        // WhatsApp-ish: words around it, the code wrapped every 30 chars, CRLFs,
        // a non-breaking space and an invisible direction mark.
        val wrapped = code.chunked(30).joinToString("\r\n")
        val msg = "‎Amy: Join my Link Play room! Paste this in the app:\n\n$wrapped\n \nsee you there 😀"
        val back = asInvite(Codes.find(msg))
        assertEquals("Amy", back.hostName)
        assertEquals(3, back.candidates.size)

        // Spaces inside the code and a word glued right after it.
        val spaced = code.chunked(7).joinToString(" ") + " ok?"
        assertEquals("Amy", asInvite(Codes.find("code: $spaced")).hostName)
        // Followed by punctuation.
        assertEquals("Amy", asInvite(Codes.find("($code).")).hostName)
        // Even the prefix split by a line break.
        assertEquals("Amy", asInvite(Codes.find(code.substring(0, 2) + "\n" + code.substring(2))).hostName)
    }

    @Test fun corruptedOrCutCodesAreDetected() {
        val code = invite().encode()
        val mid = code.length / 2
        val flipped = code.substring(0, mid) + (if (code[mid] == 'A') 'B' else 'A') + code.substring(mid + 1)
        val r1 = Codes.find(flipped)
        assertTrue("$r1", r1 is CodeError && r1.message.contains("damaged"))

        val cut = Codes.find(code.substring(0, code.length - 10) + " thanks!")
        assertTrue("$cut", cut is CodeError && cut.message.contains("damaged"))

        val none = Codes.find("hello there, no code here")
        assertTrue("$none", none is CodeError && none.message.contains("No Link Play code"))
    }

    @Test fun newerVersionIsReported() {
        val r = Codes.find("LP2I-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8")
        assertTrue("$r", r is CodeError && r.message.contains("newer version"))
    }

    @Test fun replyRoundTripAndMac() {
        val inv = invite()
        val connId = Codes.newConnId()
        val text = Codes.reply(inv.key, connId, "Ben", listOf(v6, lan))
        assertTrue(text, text.startsWith("LP1R-"))
        assertTrue("reply should be short, was ${text.length}", text.length <= 120)

        val r = asReply(Codes.find("Ben: here's my reply\n${text.chunked(25).joinToString("\n")}\nthx", inv.key))
        assertEquals(inv.roomId, r.roomId)
        assertEquals(connId, r.connId)
        assertEquals("Ben", r.guestName)
        assertEquals(listOf(v6, lan), r.candidates)
        assertTrue(r.verify(inv.key))

        // Same room id, wrong secret: someone without the invite.
        val forged = RoomKey(inv.roomId, ByteArray(16) { 7 })
        val bad = Codes.find(text, forged)
        assertTrue("$bad", bad is CodeError && bad.message.contains("doesn't match"))
        assertFalse(asReply(Codes.find(text)).verify(forged))

        // A reply for another room.
        val other = invite()
        val wrongRoom = Codes.find(text, other.key)
        assertTrue("$wrongRoom", wrongRoom is CodeError && wrongRoom.message.contains("different room"))

        // A reply made with the wrong secret is rejected by the real host.
        val fake = Codes.reply(forged, connId, "Eve", listOf(lan))
        assertTrue(Codes.find(fake, inv.key) is CodeError)

        // A flipped bit in the reply breaks the MAC.
        val mid = text.length / 2
        val flipped = text.substring(0, mid) + (if (text[mid] == 'A') 'B' else 'A') + text.substring(mid + 1)
        assertTrue(Codes.find(flipped, inv.key) is CodeError)
    }

    @Test fun firstValidCodeWins() {
        val a = invite(host = "Amy")
        val b = invite(host = "Bob")
        val broken = a.encode().dropLast(12)
        val found = asInvite(Codes.find("old: $broken\nnew: ${b.encode()}"))
        assertEquals("Bob", found.hostName)
        // Without a key the caller is a guest: an old reply above the invite doesn't hide it.
        assertEquals("Amy", asInvite(Codes.find(Codes.reply(a.key, 5, "x", emptyList()) + "\n" + a.encode())).hostName)
    }

    /** Several chat messages pasted at once: the kind the caller wants wins, then its problem, then the other kind. */
    @Test fun wantedKindWins() {
        val inv = invite()
        val reply = Codes.reply(inv.key, 77, "Ben", listOf(v6))
        val oldReply = Codes.reply(invite().key, 66, "Ben", listOf(v6)) // for an older room
        val damagedReply = reply.dropLast(12)
        val damagedInvite = inv.encode().dropLast(12)

        // The host (with its key) gets the reply even with its own invite, or an older reply, above it.
        assertEquals(77L, asReply(Codes.find("Amy: ${inv.encode()}\nBen: $reply", inv.key)).connId)
        assertEquals(77L, asReply(Codes.find("$oldReply\n$reply", inv.key)).connId)
        // A damaged reply is reported rather than the host's own invite...
        val bad = Codes.find("${inv.encode()}\n$damagedReply", inv.key)
        assertTrue("$bad", bad is CodeError && bad.message.contains("reply code is damaged"))
        // ...but with no reply at all the invite comes back, so the UI can say what was pasted.
        assertEquals(inv.roomId, asInvite(Codes.find("only ${inv.encode()}", inv.key)).roomId)

        // The guest's side, the other way round.
        val badInvite = Codes.find("$damagedInvite\n$reply")
        assertTrue("$badInvite", badInvite is CodeError && badInvite.message.contains("invite code is damaged"))
        assertEquals(77L, asReply(Codes.find("only $reply")).connId)
        assertTrue(Codes.find("$damagedReply nothing else") is CodeError)

        // Messenger gaps inside a reply's prefix too (NBSP, zero-width space, narrow NBSP, line separator).
        val gappy = "L P​1 R -" + reply.removePrefix("LP1R-")
        assertEquals(77L, asReply(Codes.find("Ben: $gappy", inv.key)).connId)
    }

    @Test fun oversizeNamesAreTruncated() {
        val inv = invite(host = "A".repeat(50), room = "R".repeat(100))
        assertEquals("A".repeat(20), inv.hostName)
        assertEquals("R".repeat(32), inv.roomName)
        val back = asInvite(Codes.find(inv.encode()))
        assertEquals("A".repeat(20), back.hostName)
        assertEquals("R".repeat(32), back.roomName)

        // Multi-byte characters are never split: 7 emoji = 28 bytes -> 5 fit in 20.
        val emoji = "😀".repeat(7)
        val e = invite(host = emoji, room = "é".repeat(40))
        assertEquals("😀".repeat(5), e.hostName)
        assertEquals("é".repeat(16), e.roomName)
        val eb = asInvite(Codes.find(e.encode()))
        assertEquals("😀".repeat(5), eb.hostName)
        assertEquals("é".repeat(16), eb.roomName)
        assertFalse(eb.hostName.contains('�'))

        // Control characters are stripped; a raw Invite with long names still encodes within limits.
        val raw = Invite(1, ByteArray(16), ByteArray(8), 2, "Bad\nName\u0007" + "x".repeat(40), "y".repeat(60), listOf(lan))
        val rb = asInvite(Codes.find(raw.encode()))
        assertEquals("BadName" + "x".repeat(13), rb.hostName)
        assertEquals(32, rb.roomName.length)

        val reply = asReply(Codes.find(Codes.reply(inv.key, 9, "G".repeat(40), emptyList()), inv.key))
        assertEquals("G".repeat(20), reply.guestName)
    }

    @Test fun candidateLimitsAndDuplicates() {
        val many = (1..12).map { Candidate(InetSocketAddress(InetAddress.getByName("10.0.0.$it"), 4000 + it), Candidate.Kind.LAN) }
        val inv = Codes.newInvite("Amy", "Room", TEST_ROM, 2, many + many)
        assertEquals(Codes.MAX_CANDIDATES, inv.candidates.size)
        val back = asInvite(Codes.find(inv.encode()))
        assertEquals(many.take(8), back.candidates)
        assertEquals(2, back.maxPlayers)
        // An unresolved address can't go into a code.
        val unresolved = Candidate(InetSocketAddress.createUnresolved("example.com", 1), Candidate.Kind.LAN)
        assertEquals(listOf(lan), Codes.newInvite("a", "b", TEST_ROM, 2, listOf(unresolved, lan)).candidates)
    }

    @Test fun connIdsAreRandomAndNonZero() {
        val ids = (1..100).map { Codes.newConnId() }.toSet()
        assertEquals(100, ids.size)
        assertFalse(0L in ids)
    }
}
