package com.linkplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Invite and reply codes as they come back from a chat: alone, wrapped, quoted, or percent-encoded. */
class CodeTextTest {
    private val v6 = Candidate(InetSocketAddress(InetAddress.getByName("2401:4900:1c2a:7f3e:a1b2:c3d4:e5f6:789a"), 50123), Candidate.Kind.IPV6)
    private val lan = Candidate(InetSocketAddress(InetAddress.getByName("192.168.1.23"), 50123), Candidate.Kind.LAN)

    private fun invite() = Codes.newInvite("Amy", "Amy's room", TEST_ROM, 4, listOf(v6, lan))

    private fun asInvite(r: CodeResult): Invite {
        if (r is CodeError) throw AssertionError("expected an invite, got: ${r.message}")
        return r as Invite
    }

    private fun asReply(r: CodeResult): Reply {
        if (r is CodeError) throw AssertionError("expected a reply, got: ${r.message}")
        return r as Reply
    }

    /** Every character of [s] as %XX, the way some apps hand shared text on. */
    private fun encodeAll(s: String) = s.toByteArray().joinToString("") { "%%%02X".format(it.toInt() and 0xff) }

    @Test fun findsTheCodeAloneAndInAChat() {
        val inv = invite()
        val code = inv.encode()
        val back = asInvite(Codes.find(code))
        assertEquals(inv.roomId, back.roomId)
        assertEquals(listOf(v6, lan), back.candidates)
        // As a chat app quotes it, wrapped and with words around it.
        assertEquals(inv.roomId, asInvite(Codes.find("Amy: ${code.chunked(40).joinToString("\n")}\nsee you")).roomId)
    }

    @Test fun findsAPercentEncodedCode() {
        val inv = invite()
        val code = inv.encode()
        // Only the dash encoded, the whole code encoded, with encoded text after it.
        for (text in listOf(
            code.replace("-", "%2D"),
            encodeAll(code),
            "join%20me%3A%20" + encodeAll(code) + "%0Asee you",
        )) {
            assertEquals(text, inv.roomId, asInvite(Codes.find(text)).roomId)
        }
        // A stray % that isn't an escape changes nothing.
        assertEquals(inv.roomId, asInvite(Codes.find("100% sure: $code %zz %")).roomId)
    }

    @Test fun repliesReachTheHost() {
        val inv = invite()
        val reply = Codes.reply(inv.key, 77, "Ben", listOf(v6))
        val r = asReply(Codes.find("Ben: $reply", inv.key))
        assertEquals(77L, r.connId)
        assertTrue(r.verify(inv.key))
        // Percent-encoded too, and with the host's own invite quoted above it.
        assertEquals(77L, asReply(Codes.find(inv.encode() + "\n" + encodeAll(reply), inv.key)).connId)
        // A guest that pastes a reply learns what it is.
        assertTrue(Codes.find(reply) is Reply)
    }

    @Test fun aDamagedCodeStillSaysWhatIsWrong() {
        val code = invite().encode()
        val r = Codes.find(code.substring(0, code.length / 2))
        assertTrue(r is CodeError)
        assertTrue((r as CodeError).message, r.message.contains("damaged"))
        assertTrue(Codes.find("no code here") is CodeError)
    }
}
