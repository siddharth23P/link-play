package com.linkplay.desktop

import com.linkplay.app.Native
import com.linkplay.app.net.CodeError
import com.linkplay.app.net.CodeResult
import com.linkplay.app.net.Codes
import com.linkplay.app.net.Invite
import com.linkplay.app.net.LinkBackend
import com.linkplay.app.net.LinkListener
import com.linkplay.app.net.Reply
import com.linkplay.app.net.RomId
import com.linkplay.app.net.RoomPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * What the link session needs from the rest of the app (the window implements it; so does the command
 * line test tool). Every method is called on the Swing event thread.
 */
interface LinkHost {
    /**
     * Makes sure the game is loaded, running and paused, then calls [onReady] (at once if it already is).
     * Loading and resuming the last snapshot may take a moment: do that off the event thread and call
     * [onReady] on it afterwards. If the game can't start, tell the user and never call [onReady].
     */
    fun ensureGameLoaded(onReady: () -> Unit)

    /** True once the game is loaded, started and (if it resumes a snapshot) that snapshot is in. */
    val gameLoaded: Boolean

    /** Takes the "before the link" snapshot (AutoSave.Kind.BEFORE_LINK) and calls [done] on the event thread, whatever the outcome. */
    fun beforeLinkSnapshot(done: () -> Unit)

    /** Something the link screens show changed (state in [LinkSession], or the link's own state): redraw them. */
    fun linkChanged()

    /** A short message that goes away by itself (a toast). */
    fun notice(text: String)

    /**
     * The link ended or could not start, and the user should be told (a dialog). The room is gone already.
     * [LinkSession.retryInvite] is set when "Try again" makes sense.
     */
    fun linkEnded(title: String, message: String)

    fun showGameScreen()

    fun playerName(): String

    /** True while the game screen is in front. A linked game keeps running behind the other screens. */
    val onGameScreen: Boolean get() = false

    /** Someone left but the room is still up. The game may be stuck waiting for them: offer the soft reset. */
    fun partnerLeft(name: String) = notice("$name left the link. If the game is stuck waiting for them, soft reset.")
}

/**
 * The link play glue between the screens, the emulator and [DesktopLink]: the desktop twin of the
 * link code in the phone app's MainActivity. It starts and ends rooms, attaches the NetLink cable at the right
 * moment (after the "before the link" snapshot), shuttles the cable's traffic to the room, and turns what the
 * room says into notes, notices and dialogs for the screens through [LinkHost].
 *
 * Everything here runs on the Swing event thread (call it from there), except the link-poll thread and
 * the frame delivery to the core. The state the screens read ([link], [inRoom], [roster], [linkNote]...) is
 * written there only, and most of it is @Volatile so a test tool can look at it from another thread.
 *
 * A typical use:
 * ```
 * val session = LinkSession(host, emu, { RomStore.current() })
 * session.hostGame { showInvite() }            // or session.joinInvite(invite) { showJoining() }
 * // ... the host's linkChanged() redraws from session.link / session.roster / session.linkNote
 * session.leaveRoom()                          // Leave room / Close room / Cancel
 * // quit: autosave first (only when !session.blocksAutosave), then
 * session.shutdown(); session.awaitShutdown(); autosave.shutdown(); emu.stop()
 * ```
 * See the "Notes for the screens" in each function.
 */
class LinkSession(
    private val host: LinkHost,
    private val emu: Emulator,
    private val rom: () -> RomInfo?,
    /** The user allowed asking STUN servers (Advanced). Read when a link starts. */
    private val askStun: () -> Boolean = { Settings.stun },
) {
    /** What [hostGame] and [joinInvite] decided. */
    enum class Begin {
        /** The room is being started: the `started` callback runs (on the event thread) once the screen should switch. */
        STARTING,
        /** Already in that very room, still connecting or hosting: show the invite (host) or joining (guest) screen. */
        SHOW_LINK,
        /** Guest: already in that room: show the game. */
        SHOW_GAME,
        /** Another room is open: ask [leaveQuestion], then [leaveRoom] and call again. */
        NEEDS_LEAVE,
        /** No ROM yet: show the setup screen. */
        NO_ROM,
        /** Nothing to do; the user has been told why (a notice or dialog through the host). */
        REFUSED,
    }

    // ---- what the screens read ----------------------------------------------------------------

    /** The room or connection attempt in progress; null when there is none. */
    @Volatile var link: DesktopLink? = null
        private set

    /** The cable is attached and we are in a room (the roster is in). Fast forward and saving are off meanwhile. */
    @Volatile var inRoom = false
        private set

    /** The NetLink cable is attached to the core. */
    @Volatile var linkAttached = false
        private set

    /** The "before the link" snapshot is being taken (the cable goes in after it). */
    @Volatile var linkSnapping = false
        private set

    /** Our player number minus one; -1 while not in a room. The host is always 0. */
    @Volatile var linkId = -1
        private set

    /** Everyone in the room, as the room last said. */
    @Volatile var roster: List<RoomPlayer> = emptyList()
        private set

    /** `Room "Ash's room" · P1 Ash · P2 Bo`; empty when not in a room. */
    @Volatile var roomLabel = ""
        private set

    /**
     * The latest news line of the link ("Bo connected directly", "Ash left the room", a changed-network advice).
     * After a failure the failure's text stays here until the next link starts (for the home screen's status line).
     */
    @Volatile var linkNote = ""
        private set

    /** Guest: the invite of the room we are in or last tried. */
    @Volatile var lastInvite: Invite? = null
        private set

    /** Set (before [LinkHost.linkEnded]) when the ended attempt can be repeated: the invite for a "Try again" button. */
    @Volatile var retryInvite: Invite? = null
        private set

    val hosting: Boolean get() = link?.hosting == true

    /** A room or attempt exists, so hosting or joining another needs [leaveRoom] first. */
    val busy: Boolean get() = inRoom || link != null

    /**
     * Periodic and leaving snapshots must not be taken now (the cable is in, or about to go in).
     * Same rule as the phone's autoSnapshot.
     */
    val blocksAutosave: Boolean get() = inRoom || linkAttached || linkSnapping

    /** A linked game must keep running (muted) behind other screens and in the background, or the others' games stall. */
    val linkedGameRunning: Boolean get() = inRoom && host.gameLoaded

    /** Guest: the reply code on screen was handed out (copied); the screens use it to say "reply code sent". */
    val replySent: Boolean get() = link?.let { sentReplyVersion >= 0 && sentReplyVersion == it.replyVersion } == true

    /** Frames the room sent to our core and bytes our core sent to the room (zero until the game uses the cable). */
    val framesPushed get() = pushed.get()
    val bytesPolled get() = polled.get()

    // ---- state of the glue ---------------------------------------------------------------------

    @Volatile private var backend: LinkBackend? = null
    @Volatile private var linkGen = 0 // bumped on every link start and end: late callbacks of a finished link are dropped
    @Volatile private var pollThread: Thread? = null
    @Volatile private var closedDown = false
    @Volatile private var pollThreadForJoin: Thread? = null // the last poll thread started, so awaitShutdown can wait for it
    /** Every link [leave] or [shutdown] closed, so [awaitShutdown] also waits for goodbyes that started earlier (leave, then quit). */
    private val closing = CopyOnWriteArrayList<DesktopLink>()
    private var seenInviteVersion = 0
    private var seenReplyVersion = 0
    private var sentReplyVersion = -1
    private var warnedReplyVersion = -1
    private var linkSnapDone = false
    private var pendingJoined: JoinedArgs? = null
    private var lobbyRunning = false
    private val pushed = AtomicLong()
    private val polled = AtomicLong()

    init {
        instance = this // the shutdown hook (Main.startUp) finds the session here when the process ends without the window's quit
    }

    private class JoinedArgs(val roomName: String, val linkId: Int, val players: List<RoomPlayer>, val hosting: Boolean)

    private fun romId(r: RomInfo) = RomId(r.sha1, r.title, r.code, r.size)

    // ---- hosting and joining ---------------------------------------------------------------------

    /**
     * Opens a room on this computer. See [Begin] for what the result asks of the screen.
     *
     * Notes for the screens: when it returns [Begin.STARTING], [started] runs on the event thread once the
     * game is loaded and the room is starting: switch to the invite screen then. Its code shows up in
     * `link.code` (a moment later: the router is asked first); the screen refreshes on [LinkHost.linkChanged].
     */
    fun hostGame(started: () -> Unit = {}): Begin {
        val rom = rom() ?: return Begin.NO_ROM
        if (closedDown) return Begin.REFUSED
        if (link?.hosting == true) return Begin.SHOW_LINK
        if (inRoom || link != null) return Begin.NEEDS_LEAVE
        host.ensureGameLoaded {
            if (closedDown || link != null) return@ensureGameLoaded // a second click while the game was loading
            val name = host.playerName()
            Log.i("host: opening a room as $name")
            startLink(true, Codes.newInvite(name, "$name's room", romId(rom), 4, emptyList()))
            started()
        }
        return Begin.STARTING
    }

    /**
     * Joins the room of [inv] (from a pasted invite code). See [Begin].
     *
     * Notes for the screens: [Begin.STARTING] means switch to the joining screen from [started]; its cable
     * line, hints and reply code come from `link` ([DesktopLink.connectedVia], [DesktopLink.hostSilent],
     * [DesktopLink.replyCanHelp], [DesktopLink.code]).
     */
    fun joinInvite(inv: Invite, started: () -> Unit = {}): Begin {
        val rom = rom() ?: return Begin.NO_ROM
        if (closedDown) return Begin.REFUSED
        val l = link
        retryInvite = null
        if (l?.hosting == true && inv.roomId == l.key.roomId) {
            host.notice("That's your own room's invite. Send it to your friends.")
            return Begin.REFUSED
        }
        if (l != null && !l.hosting && inv.roomId == l.key.roomId) {
            val old = l.invite
            val same = inv.secret.contentEquals(old.secret) && inv.candidates.toSet() == old.candidates.toSet()
            if (inRoom) return Begin.SHOW_GAME
            if (same) return Begin.SHOW_LINK
            // Still connecting, and this is the host's newer invite (its network changed): the attempt
            // running now only tries the old addresses. Start over with the new ones; a new attempt also
            // means a new reply code, which the joining screen shows.
            leaveRoom()
            host.notice("Trying ${inv.hostName}'s new invite. Your reply code is new too: send it if ${inv.hostName} asks.")
            return joinInvite(inv, started)
        }
        if (!inv.romMatches(rom.sha1)) {
            val theirs = inv.romSha1Prefix.joinToString("") { "%02x".format(it) }.take(12)
            host.linkEnded("Different ROM", "${inv.hostName}'s room plays a different ROM file (fingerprint $theirs). " +
                "Yours is ${rom.title} (${rom.shortHash}). You both need the exact same ROM file.")
            return Begin.REFUSED
        }
        if (inRoom || l != null) return Begin.NEEDS_LEAVE
        host.ensureGameLoaded {
            if (closedDown || link != null) return@ensureGameLoaded
            startLink(false, inv)
            started()
        }
        return Begin.STARTING
    }

    /** The question to ask before [leaveRoom] when [Begin.NEEDS_LEAVE] came back ("Continue" / "Cancel"). */
    fun leaveQuestion(): String = if (link?.hosting == true) "This closes your room." else "This leaves your current room."

    /** True when closing the room should be confirmed first (someone else is in it). The phone asks the same. */
    val closeNeedsConfirm: Boolean get() = roster.size > 1

    private fun startLink(hosting: Boolean, invite: Invite) {
        val rom = rom() ?: return
        val gen = ++linkGen
        roster = emptyList()
        linkNote = ""
        seenInviteVersion = 0
        seenReplyVersion = 0
        sentReplyVersion = -1
        warnedReplyVersion = -1
        retryInvite = null
        if (!hosting) lastInvite = invite
        val l = DesktopLink(hosting, invite, host.playerName(), romId(rom), askStun(), Tap(gen, hosting)) { onLinkUpdate(it) }
        link = l
        l.start()
    }

    // ---- reply codes (host) ------------------------------------------------------------------

    /**
     * Host: why "Paste reply code" can't take one right now (a message for a toast), or null when it can.
     * Check this before reading the clipboard.
     */
    fun replyBlocker(): String? {
        val l = link?.takeIf { it.hosting } ?: return "Your room isn't ready yet."
        if (l.code.isEmpty()) return "Your room isn't ready yet."
        if (l.offline) return "No network right now: connect to Wi-Fi or Ethernet first."
        return null
    }

    /** Null when [r] is a reply for this room and its addresses are being probed; else what to tell the host. */
    fun replyProblem(r: CodeResult): String? = when (r) {
        is Reply -> if (addReply(r)) null else "Your room isn't ready yet. Try again in a moment."
        is Invite -> if (r.roomId == link?.key?.roomId) "That's your own invite. Your friend's reply code starts with LP1R-."
                     else "That's an invite to someone's room, not a reply code. Your friend's reply code starts with LP1R-."
        is CodeError -> r.message
    }

    /** Guest: what to tell a user who pasted [r] into "Join a game" when it is not an invite; null for an invite (go on to [joinInvite]). */
    fun joinCodeProblem(r: CodeResult): String? = when (r) {
        is Invite -> null
        is Reply -> "That's a reply code. It goes back to the friend who hosts the room; paste their invite (LP1I-…) here."
        is CodeError -> r.message
    }

    private fun addReply(r: Reply): Boolean {
        val l = link?.takeIf { it.hosting } ?: return false
        val who = r.guestName.ifBlank { "your friend" }
        when (l.addReply(r)) {
            DesktopLink.ReplyResult.NOT_READY -> return false
            // It got in with the invite alone: keep the news line ("… connected directly") as it is.
            DesktopLink.ReplyResult.CONNECTED -> host.notice("${who.replaceFirstChar { it.uppercase() }} is already connected to your room.")
            DesktopLink.ReplyResult.PROBING -> {
                Log.i("host: reply code pasted for $who")
                linkStatus("Opening a path to $who…")
                host.notice("Opening a path to $who…")
            }
        }
        return true
    }

    /**
     * Guest: the reply code on screen was copied or otherwise handed out. Call it from the Copy button
     * (the screens then say "Reply code sent"), and a "your reply code changed" advice is cleared if it was that.
     */
    fun sentReply() {
        val l = link?.takeIf { !it.hosting } ?: return
        Log.i("guest: reply code v${l.replyVersion} ${if (l.hostSilent) "sent after the prompt" else "sent"}")
        sentReplyVersion = l.replyVersion
        if (linkNote == replyChanged(l)) linkNote = "" // that advice is now followed
        host.linkChanged()
    }

    private fun replyChanged(l: DesktopLink) =
        "Your addresses changed, so your reply code changed. Send the new one to ${l.invite.hostName}."

    // ---- the link's events ---------------------------------------------------------------------------

    /**
     * A link's events (transport / room threads). Once that link ended ([gen] is stale) they are dropped.
     * Only [onLinkFrame] stays off the event thread: it goes straight to the core.
     */
    private inner class Tap(private val gen: Int, private val hosting: Boolean) : LinkListener {
        private val live: Boolean get() = gen == linkGen

        // Never invokeAndWait here: the room waits for a running callback when it closes.
        private fun ui(block: () -> Unit) = SwingUtilities.invokeLater { if (live) block() }

        override fun onStatus(text: String) = ui { linkStatus(text) }

        override fun onJoined(roomName: String, linkId: Int, players: List<RoomPlayer>) = ui {
            link?.backend?.let { backend = it }
            joined(roomName, linkId, players, hosting)
        }

        override fun onLinkFrame(frame: ByteArray) {
            if (live) {
                Native.netPush(frame)
                pushed.incrementAndGet()
            }
        }

        override fun onPartnerLeft(name: String) = ui { partnerLeft(name) }

        // No Native.netUnplug here: linkLost -> leaveRoom detaches the cable at once anyway, and
        // an unplug still pending in a paused emulator would hit the next room's new cable.
        override fun onSessionLost(reason: String) = ui { linkLost(reason) }

        override fun onFatal(message: String) = ui { linkFailed(if (hosting) "Couldn't open the room" else "Couldn't join", message) }
    }

    /** DesktopLink's own news (event thread): code ready or remade, progress, setup failure. */
    private fun onLinkUpdate(l: DesktopLink) {
        if (l !== link) return
        l.failure?.let { return linkFailed(if (l.hosting) "Couldn't open the room" else "Couldn't join", it) }
        l.backend?.let { backend = it }
        if (l.hosting && l.inviteVersion != seenInviteVersion) {
            seenInviteVersion = l.inviteVersion
            linkNote = "Your network changed, so your invite changed. Send the new invite to friends who haven't joined yet."
            host.notice(linkNote)
        }
        if (!l.hosting && l.replyVersion != seenReplyVersion) {
            seenReplyVersion = l.replyVersion
            // Only a reply already handed out is worth a word (once per one sent): the host probes
            // its addresses, and those changed.
            if (!inRoom && sentReplyVersion >= 0 && warnedReplyVersion != sentReplyVersion) {
                warnedReplyVersion = sentReplyVersion
                linkNote = replyChanged(l)
                host.notice(linkNote)
            }
        }
        host.linkChanged()
    }

    private fun linkStatus(text: String) {
        linkNote = text
        host.linkChanged()
    }

    /** The room's roster changed (host: every change including its own; guest: Welcome and every Roster). */
    private fun joined(roomName: String, linkId: Int, players: List<RoomPlayer>, hosting: Boolean) {
        if (closedDown) return
        if (!host.gameLoaded) {
            // The game is still being set up (a snapshot is loading): attach the cable only after it has run.
            pendingJoined = JoinedArgs(roomName, linkId, players, hosting)
            host.ensureGameLoaded {
                val p = pendingJoined
                if (p != null && link != null && !closedDown) {
                    pendingJoined = null
                    joined(p.roomName, p.linkId, p.players, p.hosting)
                }
            }
            return
        }
        if (!linkAttached && !linkSnapDone) {
            // The snapshot before the cable goes in is taken off the event thread; then this runs again.
            pendingJoined = JoinedArgs(roomName, linkId, players, hosting)
            if (!linkSnapping) {
                linkSnapping = true
                host.beforeLinkSnapshot {
                    linkSnapping = false
                    val p = pendingJoined
                    if (closedDown || p == null) return@beforeLinkSnapshot
                    pendingJoined = null
                    linkSnapDone = true
                    joined(p.roomName, p.linkId, p.players, p.hosting)
                }
            }
            return
        }
        if (!linkAttached) {
            // Whatever an earlier room's cable queued after its last poll belongs to that room.
            Native.netPoll()
            Native.netAttach(linkId)
            linkAttached = true
        } else if (linkId != this.linkId) {
            Native.netSetLinkId(linkId) // someone left and the room renumbered us
        }
        this.linkId = linkId
        val wasIn = inRoom
        inRoom = true
        emu.setLinked(true) // 1x speed, fast forward off (the core holds 1x while the cable is in, too)
        startPolling()
        if (!wasIn) {
            // Waiting in a solo game, maybe in the background (sending the reply code): a linked
            // game runs even there (muted), or the others' games would wait for ours.
            if (host.gameLoaded) {
                emu.setPaused(false)
                if (!host.onGameScreen) lobbyRunning = true // leaveRoom undoes this
            }
        }
        if (hosting && wasIn && players.size > roster.size) {
            val names = players.drop(roster.size).joinToString { it.name }
            host.notice("$names joined!")
        }
        roster = players
        roomLabel = "Room \"$roomName\" · " + players.joinToString { "P${it.linkId + 1} ${it.name}${if (it.connected) "" else " (reconnecting)"}" }
        val l = link
        if (!wasIn) Log.i("${if (hosting) "host" else "guest"}: in room \"$roomName\" as P${linkId + 1} with ${players.joinToString { it.name }}")
        host.linkChanged()
        // The host stays on the invite screen; a guest goes straight to the game.
        if (!hosting && !wasIn && !host.onGameScreen) {
            val how = when {
                l == null || l.hosting || l.connectedVia == null -> ""
                l.connectedAfterReply -> " (connected after ${l.invite.hostName} added your reply code)"
                else -> " (connected directly)"
            }
            host.notice("Joined$how! In the game: Start → MPLAYER to link up.")
            host.showGameScreen()
        }
    }

    private fun partnerLeft(name: String) {
        if (link != null) linkNote = "$name left the room"
        host.linkChanged()
        host.partnerLeft(name)
    }

    /** A guest lost the friend's room after being in it. */
    private fun linkLost(reason: String) {
        val inv = lastInvite
        val hostName = inv?.hostName ?: "the host"
        Log.i("guest: lost the link to $hostName's room: $reason")
        val why = when (reason) {
            "timeout" -> "Nothing came from $hostName for 20 seconds, so the connection is gone."
            "too far behind" -> "The connection was too slow to keep up."
            else -> reason.replaceFirstChar { it.uppercase() }
        }
        leave("Link lost: $why")
        retryInvite = inv?.takeIf { !reason.contains("closed the room", ignoreCase = true) }
        host.linkEnded("Lost the link to $hostName's room", why)
    }

    /** A link could not start (no network, wrong ROM, room full, host unreachable…). */
    private fun linkFailed(title: String, message: String) {
        val retry = link?.takeIf { !it.hosting }?.invite
        Log.i("${if (link?.hosting == true) "host" else "guest"}: $title: $message")
        // GuestSession reports every transport ending before we're in the room as
        // "Couldn't reach <host>'s phone: <reason>"; only some reasons mean unreachable.
        val prefix = retry?.let { "Couldn't reach ${it.hostName}'s phone: " }
        val reason = if (prefix != null && message.startsWith(prefix)) message.removePrefix(prefix) else null
        if (retry != null && reason != null) {
            val hostName = retry.hostName
            val (text, again) = when {
                reason == "could not reach the host" || reason == "timeout" -> "Couldn't reach $hostName. " +
                    "Check that you both have a network, then try again and send $hostName the new reply code it shows." to true
                "refused" in reason -> "$hostName's room didn't let you in. It may have just closed, or too many " +
                    "players are connecting at once. Check with $hostName that the room is open, then try again." to true
                "closed the room" in reason -> "$hostName closed the room. Ask $hostName for a new invite if they host again." to false
                else -> message to true
            }
            leave(text)
            retryInvite = if (again) retry else null
            host.linkEnded(title, text)
            return
        }
        leave(message)
        retryInvite = null
        host.linkEnded(title, message)
    }

    // ---- leaving ---------------------------------------------------------------------------------

    /**
     * Closes or leaves the room: transport, port mapping and all, and detaches the cable. Safe at any time
     * (also with no room). Closing takes up to about 2 s in the background.
     */
    fun leaveRoom() = leave("")

    /** [note] stays in [linkNote] (a failure's text, for the home screen), else it is cleared. */
    private fun leave(note: String) {
        val l = link
        link = null
        linkGen++
        if (l != null) {
            closing += l // awaitShutdown waits for this goodbye too if the app quits right after
            l.close()
        }
        backend = null
        stopPolling()
        if (linkAttached) Native.netDetach()
        linkAttached = false
        linkSnapDone = false
        pendingJoined = null
        inRoom = false
        emu.setLinked(false)
        linkId = -1
        roomLabel = ""
        roster = emptyList()
        linkNote = note
        if (!host.onGameScreen && lobbyRunning) {
            if (host.gameLoaded) emu.setPaused(true) // solo games don't run behind the lobby
            lobbyRunning = false
        }
        host.linkChanged()
    }

    /**
     * Quit step one (event thread, quick): ends the link without any dialog or screen change, stops the
     * link-poll thread. Then [awaitShutdown], so the goodbyes (the room's BYE, the router's port) are out
     * before the process exits and the core stops.
     *
     * Full quit order: `autosave.snapshotNow()` first when `!blocksAutosave` (it must be queued before the
     * autosave thread shuts down), then this, then [awaitShutdown], then `autosave.shutdown()`, then `emu.stop()`.
     */
    fun shutdown() {
        if (closedDown) return // twice is fine: the second call must not lose the first one's goodbye (link is already null then)
        closedDown = true
        stopPolling()
        linkGen++
        val l = link
        link = null
        if (l != null) {
            closing += l
            l.close()
        }
        backend = null
        pendingJoined = null
        inRoom = false
        linkAttached = false
        linkSnapDone = false
    }

    /**
     * Quit step two (any thread): waits up to [timeoutMs] (one deadline for everything) for the goodbyes of
     * [shutdown] and of any room left earlier with [leaveRoom] (its goodbye may still be running), and for the
     * poll thread to end. True when all are done. Call it before `emu.stop()`: the poll thread must not touch the core after.
     */
    fun awaitShutdown(timeoutMs: Long = 3000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val left = { ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L) }
        var okLink = true
        for (l in closing.toList()) {
            if (l.awaitClosed(left().coerceAtLeast(1L))) closing.remove(l) else okLink = false
        }
        val poll = pollThreadForJoin
        try { poll?.join(left().coerceAtLeast(1L)) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        return okLink && (poll == null || !poll.isAlive)
    }

    companion object {
        /** The session of this process (the last one made), for the shutdown hook that closes the room if the app is killed. */
        @Volatile var instance: LinkSession? = null
    }

    // ---- shuttling cable traffic --------------------------------------------------------------------

    // Shuttle link traffic from the local GBA to the room. Each thread runs only while it is
    // [pollThread]: a stop followed at once by a start (leave one room, join the next) must not
    // leave the old thread draining too, or two threads could hand commands over out of order.
    private fun startPolling() {
        if (pollThread != null) return
        val t = Thread {
            val me = Thread.currentThread()
            while (pollThread === me) {
                Native.netWait(50)
                // Re-check after waking: the next room's cable may already have queued output that
                // belongs to the next polling thread.
                if (pollThread !== me) break
                val data = Native.netPoll()
                // Backend first, then the check: leaveRoom clears [backend] before stopping us and the
                // next room sets its own only after, so a batch never reaches the next room's backend.
                val b = backend
                if (data != null && b != null && pollThread === me) {
                    polled.addAndGet(data.size.toLong())
                    split(data, b)
                }
            }
        }.apply { name = "link-poll"; isDaemon = true }
        pollThread = t
        pollThreadForJoin = t
        t.start()
    }

    private fun stopPolling() {
        pollThread = null
    }

    /** netPoll's format: (u8 kind, u8 0, u16le words[1 | 8])*. Kind 1 is a handshake (one word), else a command (eight). */
    private fun split(data: ByteArray, b: LinkBackend) {
        var off = 0
        while (off + 2 <= data.size) {
            val len = if (data[off].toInt() == 1) 4 else 18
            if (off + len > data.size) break
            b.sendLink(data.copyOfRange(off, off + len))
            off += len
        }
    }

    /** For the log: what the link is doing (never a code). */
    fun linkState(): String {
        val l = link ?: return "no link"
        val who = if (l.hosting) "hosting" else "guest"
        return if (inRoom) "$who, in ${l.invite.roomName} with ${roster.size} player${if (roster.size == 1) "" else "s"}" else "$who, connecting"
    }
}
