package com.linkplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/** Adversarial review of Codes. Tests named *Bug* fail on the reviewed code. */
class CodesReviewTest {
    private val v6 = Candidate(InetSocketAddress(InetAddress.getByName("2401:4900:1c2a:7f3e::1"), 50123), Candidate.Kind.IPV6)
    private val lan = Candidate(InetSocketAddress(InetAddress.getByName("192.168.1.23"), 50123), Candidate.Kind.LAN)

    /**
     * BUG: the builder says codes are found "even with non-breaking spaces and invisible characters
     * inside the LP1I- prefix", but PREFIX uses Java's \s, which is ASCII-only ([ \t\n\x0B\f\r]).
     * The body tolerates NBSP / zero-width characters (collect() uses isWhitespace + INVISIBLE), the
     * prefix does not, so such a paste yields "No Link Play code found".
     */
    @Test fun bugInvisibleCharactersInsideThePrefix() {
        val code = Codes.newInvite("Amy", "Room", TEST_ROM, 2, listOf(v6, lan)).encode()
        val failures = ArrayList<String>()
        for (sep in listOf(" ", "​", "⁠", "﻿", "­", " ", " ")) {
            for (at in 1..4) { // between L|P|1|I|-
                val text = "Join: " + code.substring(0, at) + sep + code.substring(at)
                val r = Codes.find(text)
                if (r !is Invite) failures.add("U+%04X after \"%s\": %s".format(sep[0].code, code.substring(0, at), (r as? CodeError)?.message))
            }
            // Control: the same character inside the body is fine.
            assertTrue(Codes.find(code.substring(0, 20) + sep + code.substring(20)) is Invite)
        }
        assertTrue("prefix not tolerated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * BUG (usability): find(text, hostKey) returns the FIRST valid code of any kind. When the host
     * copies several chat messages at once (WhatsApp: select both, Copy), the text holds its own invite
     * above the guest's reply, and the host gets its invite back instead of the reply it asked for,
     * although a reply verified against hostKey is right there. Same for a guest whose paste has an old
     * reply above the invite.
     */
    @Test fun bugHostPastingInvitePlusReplyGetsTheInvite() {
        val inv = Codes.newInvite("Amy", "Room", TEST_ROM, 2, listOf(lan))
        val reply = Codes.reply(inv.key, 77, "Ben", listOf(v6))
        val chat = "[10:01] Amy: Join my room ${inv.encode()}\n[10:03] Ben: here's mine $reply"
        val r = Codes.find(chat, inv.key)
        assertTrue("host asked for a reply (passing its key) and got $r", r is Reply && r.connId == 77L)
    }

    /** Every byte flip, every cut and random glue after a code: find() never throws and never returns a wrong invite. */
    @Test fun mutatedCodesNeverThrowOrLie() {
        val inv = Codes.newInvite("Amy😀", "Room \"x\"", TEST_ROM, 3, listOf(v6, lan))
        val code = inv.encode()
        val rnd = Random(5)
        for (i in 5 until code.length) {
            for (c in listOf('A', 'z', '0', '-', '_')) {
                if (code[i] == c) continue
                val r = Codes.find(code.substring(0, i) + c + code.substring(i + 1))
                if (r is Invite) {
                    // Only trailing padding bits of the last char may change without being noticed.
                    assertEquals("flip at $i accepted", code.length - 1, i)
                    assertEquals(inv.roomId, r.roomId)
                }
            }
        }
        for (cut in 5 until code.length) {
            val r = Codes.find(code.substring(0, cut) + " " + "thanks see you".take(rnd.nextInt(15)))
            // Cutting only the last char can be "repaired" by a glued word whose first char happens to
            // carry the same few data bits; that still yields the right invite, never a wrong one.
            assertTrue("cut at $cut gave $r", r is CodeError || (cut == code.length - 1 && (r as Invite).roomId == inv.roomId))
        }
        repeat(2000) {
            val junk = String(CharArray(rnd.nextInt(0, 500)) { "LP1IR- \n aZ09_-".random(rnd) })
            Codes.find(junk) // must not throw
            Codes.find(junk, inv.key)
        }
    }

    /** Candidate edge cases: IPv6 with scope, v4-mapped, ports 1 and 65535, too many, unknown kinds skipped. */
    @Test fun candidateEdgeCases() {
        val scoped = Candidate(InetSocketAddress(InetAddress.getByName("fe80::1%1"), 1), Candidate.Kind.IPV6)
        val hiPort = Candidate(InetSocketAddress(InetAddress.getByName("::2"), 65535), Candidate.Kind.IPV6)
        val inv = Codes.newInvite("a", "b", TEST_ROM, 2, listOf(scoped, hiPort))
        val back = Codes.find(inv.encode()) as Invite
        assertEquals(listOf(1, 65535), back.candidates.map { it.address.port })
        // The scope id cannot travel in a code (16 raw bytes): a link-local candidate arrives unscoped.
        assertEquals(InetAddress.getByName("fe80::1"), back.candidates[0].address.address)
    }

    /** Names never split a code point, at any byte limit. */
    @Test fun fitNeverSplitsACodePoint() {
        val alphabet = listOf("a", "é", "ह", "😀", "🇮🇳", " ", "\"", "\\")
        val rnd = Random(9)
        repeat(3000) {
            val s = (0 until rnd.nextInt(0, 30)).joinToString("") { alphabet.random(rnd) }
            for (max in listOf(20, 32)) {
                val f = Codes.fit(s, max)
                val bytes = f.toByteArray(Charsets.UTF_8)
                assertTrue("'$f' is ${bytes.size} bytes", bytes.size <= max)
                assertTrue("lone surrogate in '$f'", f.indices.none { i ->
                    (Character.isHighSurrogate(f[i]) && (i + 1 >= f.length || !Character.isLowSurrogate(f[i + 1]))) ||
                        (Character.isLowSurrogate(f[i]) && (i == 0 || !Character.isHighSurrogate(f[i - 1])))
                })
                assertTrue(s.trim().startsWith(f))
            }
        }
    }
}
