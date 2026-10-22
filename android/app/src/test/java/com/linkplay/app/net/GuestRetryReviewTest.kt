package com.linkplay.app.net

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GuestSession over the real UdpTransport on loopback. Kept apart from RoomReviewTest so that file
 * builds without the transport.
 */
class GuestRetryReviewTest {
    /**
     * BUG (GuestSession + UdpTransport contract): a guest's reply code carries its connId and its
     * transport's port, so "Try again" after a failed attempt naturally reuses both. UdpTransport keeps
     * a tomb for every finished connId and connect() on a tombed id returns silently, so the second
     * GuestSession never gets onJoined, onFatal or onSessionLost: the UI hangs on "Connecting…" forever,
     * even though the host is up now. Transport.connect promises onClosed when no path works, and
     * GuestSession has no timeout of its own.
     */
    @Test fun bugRetryWithTheSameConnIdHangsForever() {
        val key = UdpTestKit.newKey()
        val hostT = UdpTransport(key, true, UdpTestKit.opts())
        val guestT = UdpTransport(key, false, UdpTestKit.opts().copy(connectTimeoutMs = 300))
        val room = HostRoom("Room", "Amy", TEST_ROM, 2, LinkRecorder("host"))
        try {
            val invite = Invite(key.roomId, key.secret, Codes.sha1Prefix(TEST_ROM.sha1)!!, 2, "Amy", "Room",
                listOf(UdpTestKit.cand(UdpTestKit.V4, hostT.port)))
            val connId = Codes.newConnId()

            // Attempt 1: the host app has not started accepting yet, so this attempt times out.
            val first = LinkRecorder("try1")
            GuestSession(invite, "Ben", TEST_ROM, guestT, connId, first).start()
            waitUntil("first attempt gave up") { first.fatal != null }

            // The host is ready now; the user taps "Try again" (same transport and connId as in the reply code).
            hostT.setAcceptor(room::acceptor)
            val second = LinkRecorder("try2")
            GuestSession(invite, "Ben", TEST_ROM, guestT, connId, second).start()
            Thread.sleep(1500) // 5x the connect timeout

            // Control: a fresh connId on the same transport reaches the same host at once.
            val third = LinkRecorder("try3")
            val fresh = GuestSession(invite, "Cy", TEST_ROM, guestT, Codes.newConnId(), third)
            fresh.start()
            waitUntil("fresh connId joined") { third.linkId == 1 }

            val heard = second.joins.isNotEmpty() || second.fatal != null || second.lost != null
            assertTrue("second attempt with the same connId: no onJoined/onFatal/onSessionLost after 1.5 s " +
                "(statuses ${second.statuses})", heard)
            fresh.leave()
        } finally {
            room.close()
            guestT.close()
            hostT.close()
        }
    }
}
