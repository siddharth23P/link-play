package com.linkplay.desktop.ui

import com.linkplay.app.Native
import com.linkplay.app.net.Candidate
import com.linkplay.app.net.Codes
import com.linkplay.app.net.Invite
import com.linkplay.app.net.RoomPlayer
import com.linkplay.desktop.AppInfo
import com.linkplay.desktop.AutoSave
import com.linkplay.desktop.DesktopNet
import com.linkplay.desktop.Emulator
import com.linkplay.desktop.LinkHost
import com.linkplay.desktop.LinkSession
import com.linkplay.desktop.Log
import com.linkplay.desktop.RomInfo
import com.linkplay.desktop.RomStore
import com.linkplay.desktop.Settings
import com.linkplay.desktop.moveReplacing
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Graphics
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max

/** What the controller needs from the window around it. */
interface WindowOps {
    val frame: Frame?
    val isFullScreen: Boolean
    fun setFullScreen(on: Boolean)
    /** Sizes the window for [Settings.scale], kept on the screen. True when the window really changed size. */
    fun resizeForScale(): Boolean
    fun closeWindow()
}

object NoWindow : WindowOps {
    override val frame: Frame? = null
    override val isFullScreen = false
    override fun setFullScreen(on: Boolean) {}
    override fun resizeForScale() = false
    override fun closeWindow() {}
}

/** The window's content: the screens, the dialog layer and the toast layer, all as big as the window. */
class Root(private val content: JComponent, private val overlay: JComponent, private val toasts: JComponent) : JLayeredPane() {
    init {
        isOpaque = true
        // `as Any`: the layer is the constraint, not an index (Kotlin would pick add(Component, int) otherwise).
        add(content, DEFAULT_LAYER as Any)
        add(overlay, MODAL_LAYER as Any)
        add(toasts, POPUP_LAYER as Any)
    }

    override fun doLayout() {
        for (c in components) c.setBounds(0, 0, width, height)
    }

    override fun getPreferredSize() = Dimension(1000, 700)

    override fun paintComponent(g: Graphics) {
        g.color = Pal.bg
        g.fillRect(0, 0, width, height)
    }
}

/**
 * The app's brain on the screen side: which screen is up, the game's life (load, resume, run, pause),
 * the keyboard, the menu, the dialogs, and the glue to [LinkSession]. The screens themselves only draw.
 *
 * Everything here runs on the Swing event thread, except where a thread is named.
 */
class Shell(val emu: Emulator, var win: WindowOps = NoWindow) : LobbyActions, GameHost, KeyActions, LinkHost {
    enum class Screen { SETUP, HOME, GAME, INVITE, JOINING }

    var screen = Screen.SETUP
        private set
    var rom: RomInfo? = RomStore.current()
        private set

    val autosave = AutoSave(emu) { rom?.sha1 }
    val session = LinkSession(this, emu, { rom })
    val keys = KeyRouter(this)

    // The screens.
    private val setup = SetupScreen(this)
    val home = HomeScreen(this)
    private val invite = InviteScreen(this)
    private val joining = JoiningScreen(this)
    val gameView = GameView(this)
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)
    val overlay: Overlay
    val toasts = ToastLayer()
    val dialogs: Dialogs
    val root: Root

    // The game's life.
    internal var gameReady = false
    private var loadingGame = false
    private var resumeArmed = true
    private var skipResumeOnce = false
    private val readyQueue = ArrayList<() -> Unit>()
    private var minimised = false
    private var windowActive = true
    private var wasBackground = false
    private var busyImport = false
    @Volatile var quitting = false
        private set

    // What the screens show besides the link's own state.
    private var transientStatus: String? = null
    private var statusTimer: Timer? = null
    private var homeNote: String? = null
    private var netText = "Network: …"
    private var thumb: Pair<String, BufferedImage?>? = null
    private var menuModal: Modal? = null
    private val menuPanel = MenuPanel { closeMenu() }
    private var lastTick = System.currentTimeMillis()
    private val ticker = Timer(AutoSave.TICK_MS.toInt()) { tick() }

    // Snapshots (no link): pretend states.
    internal var demoInvite: InviteModel? = null
    internal var demoJoining: JoiningModel? = null
    internal var demoHome: HomeModel? = null

    init {
        cards.isOpaque = false
        cards.add(setup, Screen.SETUP.name)
        cards.add(home, Screen.HOME.name)
        cards.add(gameView, Screen.GAME.name)
        cards.add(invite, Screen.INVITE.name)
        cards.add(joining, Screen.JOINING.name)
        overlay = Overlay(cards)
        dialogs = Dialogs(overlay) { toast(it) }
        root = Root(cards, overlay, toasts)
        overlay.onChanged = {
            if (overlay.isOpen) keys.releaseAll()
            applyRunState()
        }
        overlay.restoreFocus = { focusScreen() }
        emu.frameSink = { gameView.offerFrame(it) }
        loadScene()
        val nameCommit = object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { rememberName() }
        }
        home.nameField.addFocusListener(nameCommit)
        home.nameField.addActionListener { rememberName() }
        applyChrome()
        showScreen(if (rom == null) Screen.SETUP else Screen.HOME)
        ticker.start()
    }

    // ---- the window around ------------------------------------------------------------------

    /** The title bar of a Mac window sits on the content; full screen has none. */
    fun applyChrome() {
        Chrome.topInset = if (Fonts.mac && !win.isFullScreen) 28 else 0
        setup.topInset = Chrome.topInset
        home.topInset = Chrome.topInset
        invite.topInset = Chrome.topInset
        joining.topInset = Chrome.topInset
        gameView.topInset = if (win.isFullScreen) 0 else Chrome.topInset
        gameView.fullScreen = win.isFullScreen
        overlay.topInset = Chrome.topInset
        gameView.repaint()
    }

    fun windowActivated(active: Boolean) {
        windowActive = active
        if (!active) keys.releaseAll() else gameView.poke()
        applyRunState()
    }

    fun windowMinimised(on: Boolean) {
        minimised = on
        if (on) keys.releaseAll()
        applyRunState()
    }

    private fun background() = minimised || (Settings.muteInBackground && !windowActive)

    fun toast(text: String) = toasts.show(text)

    private fun toastLong(text: String) = toasts.show(text, long = true)

    override fun backgroundChanged() = applyRunState()

    private fun focusScreen() {
        if (screen == Screen.GAME) gameView.requestFocusInWindow()
    }

    // ---- screens ----------------------------------------------------------------------------

    fun showScreen(s: Screen) {
        if (quitting) return
        if (screen == Screen.GAME && s != Screen.GAME) leaveGameMode()
        val entering = s == Screen.GAME && screen != Screen.GAME
        screen = s
        cardLayout.show(cards, s.name)
        when (s) {
            Screen.SETUP -> {}
            Screen.HOME -> { home.showName(Settings.name); refreshHome(); home.scrollToTop() }
            Screen.GAME -> {}
            Screen.INVITE -> { refreshInvite(); invite.scrollToTop() }
            Screen.JOINING -> { refreshJoining(); joining.scrollToTop() }
        }
        if (entering) enterGameMode()
        applyRunState()
        if (s == Screen.GAME) gameView.requestFocusInWindow()
        else SwingUtilities.invokeLater { if (screen == s) root.requestFocusInWindow() }
    }

    private fun enterGameMode() {
        emu.setFastForward(false)
        emu.refreshFrame()
        gameView.poke()
        lastTick = System.currentTimeMillis()
    }

    /** Leaving the game screen for the lobby: keep the last picture for the backdrop, take a quiet snapshot. */
    private fun leaveGameMode() {
        keys.releaseAll()
        if (gameReady) {
            saveScene()
            autoSnapshot(true)
        }
    }

    /** Runs or pauses the game for what is going on: screen, window, dialogs, link. */
    fun applyRunState() {
        if (!gameReady) return
        val bg = background()
        val want = when {
            session.inRoom -> true // a linked game keeps running behind everything, or the others' games stall
            screen == Screen.GAME && !bg && !overlay.isOpen -> true
            else -> false
        }
        if (emu.isPaused == want) emu.setPaused(!want)
        emu.mute(bg)
        if (bg && !wasBackground && !session.blocksAutosave) {
            if (screen == Screen.GAME) saveScene()
            autoSnapshot(true)
        }
        wasBackground = bg
    }

    // ---- the lobby's words ----------------------------------------------------------------------

    private fun homeText(): String {
        val l = session.link
        return when {
            l?.hosting == true -> "Hosting ${l.invite.roomName} · ${max(1, session.roster.size)}/${l.invite.maxPlayers} players"
            l != null && !session.inRoom -> LinkText.guestWait(l, session.replySent, "click Show reply code and send it")
            session.inRoom -> "In ${session.roomLabel}"
            session.linkNote.isNotEmpty() -> session.linkNote
            else -> homeNote ?: ""
        }
    }

    private fun homeModel(): HomeModel? {
        demoHome?.let { return it }
        val r = rom ?: return null
        val resumeFile = if (!gameReady && resumeArmed) autosave.newestUsable() else null
        val sub = when {
            session.inRoom -> "In ${session.roomLabel}"
            gameReady -> "Game running"
            loadingGame -> "Starting the game…"
            resumeFile != null -> "Continue · ${autosave.agoText(resumeFile)}"
            RomStore.hasSave(r) -> "Continue from in-game save"
            else -> "New game"
        }
        val l = session.link
        val mode = when {
            l == null -> 0
            l.hosting -> 1
            !session.inRoom -> 2
            else -> 3
        }
        return HomeModel(
            romTitle = r.title.trim().ifEmpty { r.code },
            thumb = lastSceneImage(r),
            sub = sub,
            primary = if (session.inRoom) "Return to game" else if (loadingGame) "Starting…" else "Play",
            primaryEnabled = !loadingGame && !busyImport,
            fromSave = !gameReady && !loadingGame && resumeArmed && resumeFile != null,
            linkMode = mode,
            status = homeText(),
            netInfo = netText,
        )
    }

    fun refreshHome() {
        homeModel()?.let { home.update(it) }
    }

    private fun inviteModel(): InviteModel? {
        demoInvite?.let { return it }
        val l = session.link?.takeIf { it.hosting } ?: return null
        val open = l.code.isNotEmpty()
        val ready = open && !l.offline // offline, the invite's addresses are dead: don't hand it out
        val me = playerName()
        val list = session.roster.ifEmpty { listOf(RoomPlayer(0, me, true)) }
        val first = list.first()
        val second = list.getOrNull(1)
        return InviteModel(
            title = if (open) "Your room is open" else "Opening your room…",
            code = when {
                ready -> l.code
                open -> "No network right now. Your invite shows here again once this computer is back online."
                else -> l.progress.ifEmpty { "Getting your invite ready…" }
            },
            codeReady = ready,
            left = "P${first.linkId + 1} ${first.name}",
            right = second?.let { "P${it.linkId + 1} ${it.name}" },
            linked = second != null,
            players = if (list.size > 2) "Also in the room: " + list.drop(2).joinToString(" · ") { "P${it.linkId + 1} ${it.name}" } +
                "   (${list.size}/${l.invite.maxPlayers})" else "${list.size}/${l.invite.maxPlayers} players",
            note = if (ready && l.progress.isNotEmpty()) l.progress else session.linkNote,
            info = if (open) LinkText.hostInfo(l, session.roster.size) else "",
        )
    }

    private fun refreshInvite() {
        inviteModel()?.let { invite.update(it) }
    }

    private fun joiningModel(): JoiningModel? {
        demoJoining?.let { return it }
        val l = session.link?.takeIf { !it.hosting } ?: return null
        val inv = l.invite
        val hostName = inv.hostName
        val n = inv.candidates.size
        val via = l.connectedVia
        val me = playerName()
        val lines = mutableListOf(
            when {
                via != null -> "Joining ${inv.roomName}…"
                n == 0 -> "$hostName's invite has no address to try"
                else -> "Trying $n address${if (n == 1) "" else "es"} (${inv.candidates.map { LinkText.kindName(it.kind) }.distinct().joinToString()})"
            }
        )
        l.net?.let { net ->
            lines += if (!net.online) "No network: connect to Wi-Fi or Ethernet." else LinkText.linkUses(net)
            if (via == null) LinkText.guestHint(l, net)?.let { lines += it }
            if (net.online) LinkText.strictAdvice(l, net)?.let { lines += it }
        }
        val sent = session.replySent
        val (prompt, style) = when {
            via != null -> "" to Prompt.NONE
            l.hostSilent && l.replyCanHelp && sent -> "Reply code sent. Once $hostName clicks Paste reply code, you should connect within seconds." to Prompt.LOUD_OK
            l.hostSilent && l.replyCanHelp -> "No answer from $hostName yet. Send $hostName your reply code now." to Prompt.LOUD_BAD
            l.hostSilent -> "No answer from $hostName yet." to Prompt.QUIET
            else -> "If it doesn't connect within a few seconds, send this reply code back to $hostName:" to Prompt.QUIET
        }
        val made = l.code.isNotEmpty()
        val ready = made && !l.offline
        return JoiningModel(
            title = when {
                via == null -> "Connecting to ${inv.roomName}…"
                l.connectedAfterReply -> "Connected after $hostName added your reply code"
                else -> "Connected directly"
            },
            left = "P1 $hostName",
            right = if (session.linkId >= 0) "P${session.linkId + 1} $me" else "You · $me",
            linked = via != null,
            info = lines.joinToString("\n"),
            prompt = prompt,
            promptStyle = style,
            code = when {
                ready -> l.code
                made -> "No network right now. Your reply code shows here again once this computer is back online."
                else -> l.progress.ifEmpty { "Getting your reply code ready…" }
            },
            codeReady = ready,
            codeVisible = via == null,
            note = session.linkNote,
        )
    }

    private fun refreshJoining() {
        joiningModel()?.let { joining.update(it) }
    }

    private fun rememberName() {
        if (screen == Screen.HOME || demoHome != null) {
            val typed = home.name()
            if (typed != Settings.name) Settings.name = typed
        }
    }

    // ---- the game's life -------------------------------------------------------------------------

    override val gameLoaded: Boolean get() = gameReady

    override val onGameScreen: Boolean get() = screen == Screen.GAME

    /**
     * Starts the game if it isn't running (off the event thread), then runs [onReady] on it. The first start after the
     * app opens continues the newest snapshot, before any cable goes in. Never calls [onReady] when the game can't start.
     */
    override fun ensureGameLoaded(onReady: () -> Unit) {
        if (rom == null) return
        if (gameReady) return onReady()
        readyQueue += onReady
        if (loadingGame) return
        loadingGame = true
        refreshHome()
        val r = rom!!
        Thread({
            val ok = emu.load(r.file, RomStore.saveFile(r))
            SwingUtilities.invokeLater { gameLoadDone(ok) }
        }, "game-load").start()
    }

    private fun gameLoadDone(ok: Boolean) {
        if (quitting) return
        if (!ok) {
            loadingGame = false
            readyQueue.clear()
            refreshHome()
            toastLong("Could not start the game.")
            return
        }
        emu.setVolume(Settings.volume)
        emu.start()
        emu.setPaused(true)
        soundCheck()
        autosave.booted()
        val wanted = resumeArmed && !skipResumeOnce
        val f = if (wanted) autosave.newestUsable() else null
        // An autosave older than the in-game save would undo it: start from the in-game save instead.
        if (wanted && f == null && autosave.newest() != null) toastLong("Started from your in-game save: it is newer than the last autosave.")
        resumeArmed = false
        skipResumeOnce = false
        if (f == null) return gameReadyNow()
        val ago = autosave.agoText(f)
        autosave.load(f) { ok2 ->
            SwingUtilities.invokeLater {
                if (quitting) return@invokeLater
                if (ok2) { autosave.booted(); toastLong("Continued where you left off (saved $ago)") }
                else toastLong("Couldn't load the save; starting normally.")
                gameReadyNow()
            }
        }
    }

    /** The sound device opens in the background: a moment after the start, say once if there is none (the game plays on, silent). */
    private fun soundCheck() {
        Timer(1500) { if (!quitting && soundProblem() != null) toastLong(NO_SOUND) }.apply { isRepeats = false; start() }
    }

    /** Why the game is silent when it should not be (no device, or it stopped); null while the sound is fine or still opening. */
    private fun soundProblem(): String? = emu.audio?.problem

    private fun gameReadyNow() {
        gameReady = true
        loadingGame = false
        emu.refreshFrame()
        val queued = ArrayList(readyQueue)
        readyQueue.clear()
        applyRunState()
        queued.forEach { it() }
        refreshHome()
    }

    private fun resetGame() {
        gameReady = false
        loadingGame = false
        readyQueue.clear()
        resumeArmed = true
        skipResumeOnce = false
        wasBackground = false
        gameView.clearFrame()
    }

    override fun beforeLinkSnapshot(done: () -> Unit) {
        autosave.snapshot(AutoSave.Kind.BEFORE_LINK) { SwingUtilities.invokeLater(done) }
    }

    /** A periodic or leaving snapshot of the solo game; a tiny "Autosaved" in the status chip when [quiet] is false. */
    private fun autoSnapshot(quiet: Boolean) {
        if (!gameReady || loadingGame || session.blocksAutosave) return
        autosave.snapshot(AutoSave.Kind.AUTO) { ok ->
            if (ok && !quiet) SwingUtilities.invokeLater { if (!session.inRoom) flashStatus("Autosaved", 2000) }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = now - lastTick
        lastTick = now
        val running = gameReady && screen == Screen.GAME && !background() && !overlay.isOpen
        if (autosave.tick(dt, running, session.inRoom || session.linkAttached)) autoSnapshot(false)
    }

    /** The picture's last scene goes to the lobby's backdrop and thumbnail. */
    private fun saveScene() {
        val r = rom ?: return
        val f = emu.grabFrame() ?: return
        Scene.set(f)
        val img = BufferedImage(Native.WIDTH, Native.HEIGHT, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, Native.WIDTH, Native.HEIGHT, f, 0, Native.WIDTH)
        thumb = r.sha1 to img
        try {
            val dir = RomStore.statesDir(r.sha1).apply { mkdirs() }
            val tmp = File(dir, "last-frame.png.tmp")
            ImageIO.write(img, "png", tmp)
            moveReplacing(tmp, File(dir, "last-frame.png"))
        } catch (e: Exception) {
            Log.w("last frame: ${e.message}")
        }
    }

    private fun lastSceneImage(r: RomInfo): BufferedImage? {
        thumb?.let { if (it.first == r.sha1) return it.second }
        val img = try {
            File(RomStore.statesDir(r.sha1), "last-frame.png").takeIf { it.isFile }?.let { ImageIO.read(it) }
        } catch (e: Exception) { null }
        thumb = r.sha1 to img
        return img
    }

    private fun loadScene() {
        val r = rom ?: return
        val img = lastSceneImage(r) ?: return
        if (img.width != Native.WIDTH || img.height != Native.HEIGHT) return
        Scene.set(img.getRGB(0, 0, Native.WIDTH, Native.HEIGHT, null, 0, Native.WIDTH))
    }

    private fun flashStatus(text: String, ms: Int) {
        transientStatus = text
        statusTimer?.stop()
        statusTimer = Timer(ms) { transientStatus = null; gameView.poke(); gameView.repaint() }.apply { isRepeats = false; start() }
        gameView.poke()
        gameView.repaint()
    }

    // ---- the picture's host ----------------------------------------------------------------------

    override fun statusText(): String {
        transientStatus?.let { return it }
        val l = session.link
        return when {
            session.inRoom -> session.roomLabel
            l != null && !l.hosting && l.hostSilent && l.connectedVia == null ->
                LinkText.guestWait(l, session.replySent, "send your reply code (Esc → Back to lobby → Show reply code)")
            l != null && session.linkNote.isNotEmpty() -> session.linkNote
            else -> SOLO
        }
    }

    override fun fastForwardOn() = emu.fastForward

    /** A notice (a friend left) shows for its full time, and a room still waiting for its friend keeps its label up. */
    override fun chipsHeld() = transientStatus != null || (session.inRoom && session.roster.size < 2)

    // ---- the keyboard's host ---------------------------------------------------------------------

    override fun setButtons(mask: Int) = emu.setButtons(mask)

    override fun setFastForward(on: Boolean) {
        emu.setFastForward(on)
        gameView.repaint()
    }

    override fun fastForwardAllowed() = !session.inRoom && !session.linkAttached

    override fun fastForwardRefused() = toast("Fast-forward is off during link play")

    override fun openMenu() = gameMenu()

    override fun toggleFullScreen() {
        win.setFullScreen(!win.isFullScreen)
    }

    override fun zoom(delta: Int) {
        if (win.isFullScreen) return toast("Leave full screen to change the window size.")
        val before = Settings.scale.coerceIn(1, 6)
        val s = (before + delta).coerceIn(1, 6)
        Settings.scale = s
        if (win.resizeForScale()) return toast("Window size ${s}×")
        // The window did not change: the smallest size (the step is below the least the window may be) or the largest
        // the screen has room for. Stay on the step we were on, so the next press goes the other way at once.
        Settings.scale = before
        toast(if (delta < 0) "Smallest size" else "Largest size that fits the screen")
    }

    /** Called with every key event of the window (a dispatcher): true when it was the game's or the screen's. */
    fun dispatchKey(e: KeyEvent): Boolean {
        if (quitting) return false
        if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE && !overlay.isOpen &&
            (screen == Screen.INVITE || screen == Screen.JOINING)
        ) {
            showScreen(Screen.HOME)
            return true
        }
        if (screen != Screen.GAME || overlay.isOpen) return false
        val shortcut = if (Fonts.mac) e.isMetaDown else e.isControlDown
        return when (e.id) {
            KeyEvent.KEY_PRESSED -> keys.press(e.keyCode, e.isShiftDown, shortcut)
            KeyEvent.KEY_RELEASED -> keys.release(e.keyCode)
            else -> false
        }
    }

    // ---- save states ----------------------------------------------------------------------------

    private fun slotFile(n: Int): File? = rom?.let { File(RomStore.statesDir(it.sha1), "slot$n.ss") }

    private fun slotPng(n: Int): File? = slotFile(n)?.let { File(it.parentFile, "slot$n.png") }

    private fun linked() = session.inRoom || session.linkAttached

    override fun saveSlot(n: Int) {
        if (linked()) return toast("Save states are off during link play.")
        val f = slotFile(n) ?: return
        val png = slotPng(n) ?: return
        if (!gameReady) return
        val frame = emu.grabFrame() // the picture first, so the thumbnail matches the saved moment
        emu.saveState(f) { ok ->
            if (ok) {
                autosave.stampSave(f)
                png?.delete()
                frame?.let {
                    try {
                        val img = BufferedImage(Native.WIDTH, Native.HEIGHT, BufferedImage.TYPE_INT_RGB)
                        img.setRGB(0, 0, Native.WIDTH, Native.HEIGHT, it, 0, Native.WIDTH)
                        val tmp = File(png.parentFile, png.name + ".tmp")
                        ImageIO.write(img, "png", tmp)
                        moveReplacing(tmp, png)
                    } catch (e: Exception) {
                        Log.w("slot thumbnail: ${e.message}")
                    }
                }
            }
            SwingUtilities.invokeLater { if (ok) toast("Saved to slot $n") else toastLong("Couldn't save the state. Try again.") }
        }
    }

    override fun loadSlot(n: Int) {
        if (linked()) return toast("Save states are off during link play.")
        val f = slotFile(n) ?: return
        if (!f.isFile) return toast("Slot $n is empty.")
        if (!gameReady) return
        emu.loadState(f) { ok ->
            SwingUtilities.invokeLater {
                emu.refreshFrame()
                if (ok) toast("Loaded slot $n") else toastLong("Couldn't load that state. The slot may be damaged.")
            }
        }
    }

    /** Loads a snapshot (autosave, before the link). */
    private fun loadSnapshot(f: File, doneText: String) {
        if (!gameReady) return
        val ago = autosave.agoText(f)
        autosave.load(f) { ok ->
            SwingUtilities.invokeLater {
                emu.refreshFrame()
                if (ok) { autosave.booted(); toast("$doneText (saved $ago)") } else toastLong("Couldn't load that save.")
            }
        }
    }

    /**
     * Leaves the room (detaching the cable) and loads the snapshot taken right before the link. [confirmFirst]: ask
     * before anything happens (the menu row is one click away); a caller that already asks, like the soft reset
     * dialog's own button, passes false.
     */
    private fun goBackToBeforeLink(confirmFirst: Boolean = true) {
        val f = autosave.beforeLinkFile() ?: return
        if (confirmFirst) {
            dialogs.confirm("Go back to before the link?",
                "Goes back to where the game was ${autosave.agoText(f)}, before you linked. Anything since then is lost, except what you saved in the game itself." +
                    if (session.busy) " This leaves your room." else "",
                "Go back", destructive = true) { goBackToBeforeLink(false) }
            return
        }
        val go = {
            if (session.busy) session.leaveRoom()
            loadSnapshot(f, "Back to before the link")
        }
        // The game saves by itself during some link activities (a trade, for example). Going back would undo
        // that in the running game, and saving afterwards would replace the save that holds it.
        if (autosave.matchesSave(f)) go()
        else dialogs.confirm("Your game saved during the link",
            "For example after a trade. Going back returns to before that. If you then save in the game, the save made during the link is replaced.",
            "Go back") { go() }
    }

    private fun softResetDialog(title: String = "Soft reset?", reason: String? = null) {
        val back = if (reason != null && gameReady && autosave.beforeLinkFile() != null) "Go back to before the link" to { goBackToBeforeLink(false) } else null
        dialogs.confirm(title,
            (reason?.let { "$it\n\n" } ?: "") + "Goes back to the title screen (A+B+Start+Select). Anything since your last in-game save is lost.",
            "Soft reset", destructive = true, neutral = back) { emu.softReset() }
    }

    // ---- the game menu ----------------------------------------------------------------------------

    private fun savedText(t: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
        val days = (now.get(java.util.Calendar.YEAR) * 366 + now.get(java.util.Calendar.DAY_OF_YEAR)) -
            (then.get(java.util.Calendar.YEAR) * 366 + then.get(java.util.Calendar.DAY_OF_YEAR))
        return when (days) {
            0 -> "Today $time"
            1 -> "Yesterday $time"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t)) + " $time"
        }
    }

    fun gameMenu() {
        if (!gameReady || screen != Screen.GAME || overlay.isOpen || quitting) return
        val m = Modal(menuPanel, side = true, width = 400, frosted = false, scrim = 0.42f,
            onCancel = { menuPanel.cancel() })
        menuModal = m
        overlay.show(m)
        menuMain()
    }

    private fun closeMenu() {
        val m = menuModal ?: return
        menuModal = null
        overlay.dismiss(m)
    }

    private fun page(title: String, sections: List<MenuSection>, back: (() -> Unit)?) {
        menuPanel.show(title, sections, back)
        SwingUtilities.invokeLater { menuPanel.firstFocus?.requestFocusInWindow() }
    }

    private fun menuMain() {
        val inLink = linked()
        val off = "Off during link play"
        val game = mutableListOf(
            MenuRow("Save state", enabled = !inLink, reason = off, chevron = true) { menuStates(true) },
            MenuRow("Load state", enabled = !inLink, reason = off, chevron = true) { menuStates(false) },
        )
        autosave.autoFile()?.let { auto ->
            val ago = autosave.agoText(auto)
            game += MenuRow("Load autosave ($ago)…", enabled = !inLink, reason = off) {
                closeMenu()
                val msg = if (autosave.matchesSave(auto)) "Goes back to where the game was $ago. Anything since then is lost, except what you saved in the game itself."
                else "Goes back to where the game was $ago. Your in-game save is newer than this. If you save in the game after loading, that newer save is replaced."
                dialogs.confirm("Load autosave?", msg, "Load") { loadSnapshot(auto, "Autosave loaded") }
            }
        }
        autosave.beforeLinkFile()?.let { f ->
            game += MenuRow("Go back to before the link", detail = "Saved ${autosave.agoText(f)}. Leaves the room.") {
                closeMenu()
                goBackToBeforeLink()
            }
        }
        val display = listOf(
            MenuRow("Screen: ${FILTERS[Settings.filter]}", chevron = true) { menuFilter() },
            MenuRow("Controls", chevron = true) { closeMenu(); controlsDialog() },
            MenuRow("Volume", value = "${Math.round(Settings.volume * 100)}%", chevron = true) { menuVolume() },
            MenuRow("Fast forward", value = if (emu.fastForward) "On" else "Off", enabled = !inLink,
                reason = "Off during link play", detail = "Or hold Tab") {
                emu.setFastForward(!emu.fastForward)
                menuMain()
            },
            MenuRow("Full screen", value = if (win.isFullScreen) "On" else "Off") { closeMenu(); toggleFullScreen() },
        )
        val sections = mutableListOf(MenuSection("Game", game), MenuSection("Display & controls", display))
        val l = session.link
        if (l != null || session.inRoom) {
            val rows = mutableListOf<MenuRow>()
            if (l?.hosting == true) {
                rows += MenuRow("Invite players…") { closeMenu(); showScreen(Screen.INVITE) }
                rows += MenuRow("Close room…") { closeMenu(); closeRoom() }
            } else rows += MenuRow("Leave room") { closeMenu(); leaveRoom() }
            sections += MenuSection("Link", rows)
        }
        sections += MenuSection("Other", listOf(
            MenuRow("Back to lobby") { closeMenu(); showScreen(Screen.HOME) },
            MenuRow("Soft reset…", destructive = true) { closeMenu(); softResetDialog() },
        ))
        page("Game menu", sections, null)
    }

    private fun menuFilter() {
        val cur = Settings.filter
        val rows = FILTERS.indices.map { i ->
            MenuRow(FILTERS[i], detail = FILTER_INFO[i] + if (i == Settings.DEFAULT_FILTER) " · Recommended" else "", checked = i == cur) {
                Settings.filter = i
                gameView.repaint()
                menuFilter()
            }
        }
        page("Screen", listOf(MenuSection(null, rows)), { menuMain() })
    }

    private fun menuVolume() {
        val pct = TextBlock("${Math.round(Settings.volume * 100)}%", Fonts.regular(14f), Pal.muted, align = javax.swing.SwingConstants.RIGHT)
        val slider = SliderBar(Math.round(Settings.volume * 100)) { v ->
            Settings.volume = v / 100f
            emu.setVolume(v / 100f)
            pct.text = "$v%"
        }
        val box = VBox(0, Insets(6, 10, 6, 18))
        box.put(slider)
        box.put(pct, 0)
        if (soundProblem() != null) box.put(caption(NO_SOUND, 12.5f, Pal.faint), 6)
        page("Volume", listOf(MenuSection(null, view = box)), { menuMain() })
        SwingUtilities.invokeLater { slider.requestFocusInWindow() }
    }

    private fun menuStates(save: Boolean) {
        if (linked()) { toast("Save states are off during link play."); return }
        val rows = (1..3).map { n ->
            val f = slotFile(n)
            val used = f?.isFile == true
            val img = if (used) slotPng(n)?.takeIf { it.isFile }?.let { p -> try { ImageIO.read(p) } catch (e: Exception) { null } } else null
            MenuRow("Slot $n", detail = if (used) savedText(f!!.lastModified()) else "Empty", enabled = save || used, hasThumb = true, thumb = img) {
                closeMenu()
                if (save) {
                    if (used) dialogs.confirm("Replace slot $n?", "The state saved there now is lost.", "Replace") { saveSlot(n) }
                    else saveSlot(n)
                } else {
                    val msg = if (f != null && !autosave.matchesSave(f)) "Unsaved progress since then is lost. Your in-game save is newer than this. If you save in the game after loading, that newer save is replaced."
                    else "Unsaved progress since then is lost."
                    dialogs.confirm("Load slot $n?", msg, "Load") { loadSlot(n) }
                }
            }
        }
        page(if (save) "Save state" else "Load state", listOf(MenuSection(null, rows)), { menuMain() })
    }

    fun controlsDialog() {
        dialogs.controls { keys.releaseAll() }
    }

    // ---- ROM, saves, files ---------------------------------------------------------------------

    private var lastDir: String? = null

    private fun pickFile(title: String, mode: Int, name: String?): File? {
        val d = FileDialog(win.frame, title, mode)
        lastDir?.let { d.directory = it }
        if (name != null) d.file = name
        d.isVisible = true
        val f = d.file ?: return null
        lastDir = d.directory
        return File(d.directory, f)
    }

    override fun chooseRom() {
        if (busyImport) return
        val f = pickFile("Choose your GBA ROM", FileDialog.LOAD, null) ?: return
        importRom(f)
    }

    /**
     * A file dropped on the window: a save if it ends in .sav, else a ROM. A drop is easy to do by accident, so
     * nothing changes (no game closed, no room left, no file touched) until the person has confirmed.
     */
    fun dropped(f: File) {
        if (quitting || busyImport || overlay.isOpen) return
        if (f.name.endsWith(".sav", ignoreCase = true)) {
            if (rom == null) return toast("Pick a ROM first.")
            confirmReplaceSave(f)
        } else {
            importRom(f, confirmIdle = true)
        }
    }

    private fun gameRunning() = gameReady || loadingGame || Emulator.coreLoaded

    /**
     * [confirmIdle]: ask before replacing the ROM even when no game is running (a dropped file; the picker is already
     * an explicit choice). A game that runs, or a room, is always asked about.
     */
    private fun importRom(file: File, confirmIdle: Boolean = false) {
        if (busyImport) return
        // Look at the file first: a zip or a text file must not close the running game or ask anything.
        RomStore.check(file)?.let { return toastLong(it) }
        val busy = session.busy
        val old = rom
        val replace = { snapshot: Boolean ->
            replaceFiles("Importing ROM…", snapshot = snapshot, work = { RomStore.import(file) }) { info ->
                rom = info
                thumb = null
                Scene.set(null)
                if (snapshot) loadScene()
                toast("Loaded ${info.title}")
            }
        }
        if (busy || gameRunning()) {
            dialogs.confirm("Change the ROM?", (if (busy) "This leaves your room. " else "") +
                "The game that's running closes first. Anything since your last in-game save is lost.", "Change ROM", destructive = true) {
                replace(true)
            }
        } else if (confirmIdle && old != null) {
            dialogs.confirm("Change the ROM?",
                "Replace ${old.title.trim().ifEmpty { old.code }} with ${file.name}? Your save for the old game stays in the saves folder.",
                "Change ROM") { replace(false) }
        } else {
            replace(false)
        }
    }

    override fun importSave() {
        if (busyImport) return
        if (rom == null) return toast("Pick a ROM first.")
        val f = pickFile("Choose a save file", FileDialog.LOAD, null) ?: return
        confirmReplaceSave(f)
    }

    /** The reason [file] cannot be a GBA save, or null. (The same size rule as [RomStore.importSave], looked at before anything is closed.) */
    private fun saveProblem(file: File): String? = when {
        !file.isFile -> "Could not open the file."
        file.length() !in 1..MAX_SAVE_BYTES -> "That doesn't look like a GBA save (wrong size)."
        else -> null
    }

    /**
     * The one way a save file replaces the in-game save, for the menu and for a dropped file: always asks, and
     * nothing is closed, left or deleted before the answer.
     */
    private fun confirmReplaceSave(file: File) {
        val r = rom ?: return toast("Pick a ROM first.")
        if (busyImport) return
        saveProblem(file)?.let { return toastLong(it) }
        dialogs.confirm("Replace your in-game save?",
            "${file.name} replaces this computer's save for ${r.title.trim().ifEmpty { r.code }}. Autosaves for this game are removed; " +
                "your save-state slots stay. To keep the current save, use Export save first." +
                (if (session.busy) " This leaves your room." else "") +
                (if (gameRunning()) " The running game closes first." else ""),
            "Replace", destructive = true) { importSaveFile(file) }
    }

    private fun importSaveFile(file: File) {
        val r = rom ?: return toast("Pick a ROM first.")
        if (busyImport) return
        saveProblem(file)?.let { return toastLong(it) }
        replaceFiles("Importing save…", snapshot = false, work = { RomStore.importSave(r, file); r }) { toast("Save imported") }
    }

    /**
     * Runs [work] (which replaces the ROM or the save) on its own thread after the game has been closed: Windows keeps
     * those files open for as long as a game has them. [snapshot]: take a last snapshot of the closing game first.
     */
    private fun <T> replaceFiles(status: String, snapshot: Boolean, work: () -> T, done: (T) -> Unit) {
        busyImport = true
        homeNote = status
        setup.setStatus(status)
        refreshHome()
        val snap = snapshot && gameReady && !session.blocksAutosave
        session.leaveRoom()
        resetGame()
        Thread({
            var result: T? = null
            var error: String? = null
            try {
                if (emu.isLoaded) {
                    if (snap) autosave.snapshotNow()
                    emu.stop()
                }
                result = work()
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Exception) {
                Log.e("replace files", e)
                error = e.message ?: "That didn't work."
            }
            SwingUtilities.invokeLater {
                busyImport = false
                homeNote = null
                val r = result
                if (error == null && r != null) {
                    done(r)
                    showScreen(if (rom == null) Screen.SETUP else Screen.HOME)
                } else {
                    setup.setStatus(error ?: "")
                    toastLong(error ?: "That didn't work.")
                    refreshHome()
                    showScreen(if (rom == null) Screen.SETUP else Screen.HOME)
                }
            }
        }, "rom-import").start()
    }

    override fun exportSave() {
        if (busyImport) return
        val r = rom ?: return toast("Pick a ROM first.")
        if (!RomStore.hasSave(r)) return toast("No in-game save yet: save in the game first.")
        val dest = pickFile("Export your save", FileDialog.SAVE, RomStore.exportName(r)) ?: return
        Thread({
            val msg = try {
                RomStore.exportSave(r, dest)
                "Save exported"
            } catch (e: Exception) {
                e.message ?: "Couldn't write the file."
            }
            SwingUtilities.invokeLater { toast(msg) }
        }, "save-export").start()
    }

    // ---- LobbyActions -----------------------------------------------------------------------------

    override fun play() {
        if (busyImport) return
        rememberName()
        showGameFlow()
    }

    override fun playFromInGameSave() {
        if (busyImport) return
        rememberName()
        skipResumeOnce = true
        showGameFlow()
    }

    private fun showGameFlow() {
        if (rom == null) return showScreen(Screen.SETUP)
        if (gameReady) return showScreen(Screen.GAME)
        ensureGameLoaded { showScreen(Screen.GAME) }
    }

    override fun hostGame() {
        if (busyImport) return
        rememberName()
        when (session.hostGame { showScreen(Screen.INVITE) }) {
            LinkSession.Begin.STARTING -> {}
            LinkSession.Begin.SHOW_LINK -> showScreen(Screen.INVITE)
            LinkSession.Begin.NEEDS_LEAVE -> dialogs.confirm("Host a new game?", session.leaveQuestion(), "Continue") {
                session.leaveRoom()
                hostGame()
            }
            LinkSession.Begin.NO_ROM -> showScreen(Screen.SETUP)
            LinkSession.Begin.SHOW_GAME, LinkSession.Begin.REFUSED -> {}
        }
    }

    override fun joinGame() {
        if (busyImport) return
        rememberName()
        dialogs.code("Join a game", "Paste the invite your friend sent you. The whole message is fine.", "Invite code (LP1I-…)", "Join", null) { text ->
            val r = Codes.find(text, null)
            val problem = session.joinCodeProblem(r)
            if (problem != null) problem else { joinInvite(r as Invite); null }
        }
    }

    private fun joinInvite(inv: Invite) {
        when (session.joinInvite(inv) { showScreen(Screen.JOINING) }) {
            LinkSession.Begin.STARTING -> {}
            LinkSession.Begin.SHOW_LINK -> showScreen(Screen.JOINING)
            LinkSession.Begin.SHOW_GAME -> showScreen(Screen.GAME)
            LinkSession.Begin.NEEDS_LEAVE -> dialogs.confirm("Join ${inv.roomName}?", session.leaveQuestion(), "Continue") {
                session.leaveRoom()
                joinInvite(inv)
            }
            LinkSession.Begin.NO_ROM -> showScreen(Screen.SETUP)
            LinkSession.Begin.REFUSED -> {}
        }
    }

    override fun showInvite() {
        if (session.link?.hosting == true) showScreen(Screen.INVITE) else showScreen(Screen.HOME)
    }

    override fun showJoining() {
        if (session.link?.hosting == false) showScreen(Screen.JOINING) else showScreen(Screen.HOME)
    }

    override fun showLobby() = showScreen(Screen.HOME)

    override fun startPlaying() = showScreen(Screen.GAME)

    override fun closeRoom() {
        if (!session.closeNeedsConfirm) return session.leaveRoom()
        dialogs.confirm("Close your room?", "Everyone else in it loses the link.", "Close room", destructive = true) { session.leaveRoom() }
    }

    override fun leaveRoom() {
        val guest = session.link?.hosting == false
        session.leaveRoom()
        if (guest) toast("You left the room.")
    }

    override fun copyCode() {
        val l = session.link ?: return
        val code = l.code.takeIf { it.isNotEmpty() && !l.offline } ?: return
        if (Clip.copy(code)) {
            Log.i(if (l.hosting) "host: invite copied" else "guest: reply code copied") // never the code itself
            if (!l.hosting) session.sentReply()
            if (l.hosting) invite.flashCopied() else joining.flashCopied()
        } else {
            toastLong("Couldn't copy. Select the code and copy it by hand.")
        }
    }

    override fun pasteReply() {
        session.replyBlocker()?.let { return toast(it) }
        // The clipboard is read only now, because the host asked for it.
        val text = Clip.text()
        if (text.isNullOrBlank()) return replyDialog(null)
        val problem = session.replyProblem(Codes.find(text, session.link?.key))
        if (problem != null) replyDialog(problem)
    }

    private fun replyDialog(problem: String?) {
        dialogs.code("Paste reply code", "Paste your friend's reply code (it starts with LP1R-). The whole message is fine.",
            "Reply code (LP1R-…)", "Add", problem) { text -> session.replyProblem(Codes.find(text, session.link?.key)) }
    }

    override fun advancedToggled(open: Boolean) {
        if (open) refreshNetInfo()
    }

    /** Advanced: what the network looks like (off the event thread: it may run a command). */
    private fun refreshNetInfo() {
        Thread({
            val text = try { LinkText.networkLine(DesktopNet.snapshot()) } catch (e: Exception) { "Network: unknown" }
            SwingUtilities.invokeLater { netText = text; refreshHome() }
        }, "net-info").apply { isDaemon = true }.start()
    }

    override fun openSource() {
        val url = "https://github.com/mgba-emu/mgba"
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (e: Exception) {
            Log.w("open browser: ${e.message}")
        }
        Clip.copy(url)
        toast("Copied $url")
    }

    override fun about() {
        dialogs.custom("Link Play ${AppInfo.VERSION}",
            "Game Boy Advance link cable play for computers and phones.\n\n" +
                "Emulation by mGBA (MPL-2.0); its source is at github.com/mgba-emu/mgba. This app also contains the OpenJDK " +
                "runtime (GPLv2 with the Classpath Exception) and the Kotlin standard library (Apache-2.0). The full notices are in " +
                "THIRD_PARTY_NOTICES.md.\n\nYou bring your own ROM. Link Play is not made by or connected to Nintendo.",
            listOf(DialogButton("mGBA source", DKind.NEUTRAL) { openSource() }, DialogButton("OK", DKind.PRIMARY)), null, 460)
    }

    // ---- LinkHost -------------------------------------------------------------------------------------

    override fun linkChanged() {
        when (screen) {
            Screen.INVITE -> if (session.link?.hosting == true) refreshInvite() else showScreen(Screen.HOME)
            Screen.JOINING -> if (session.link?.hosting == false) refreshJoining() else showScreen(Screen.HOME)
            Screen.HOME -> refreshHome()
            Screen.GAME -> { gameView.poke(); gameView.repaint() }
            Screen.SETUP -> {}
        }
        if (session.inRoom) keys.linkStarted()
        if (menuModal != null && overlay.current === menuModal && !menuPanel.canGoBack) menuMain()
        applyRunState()
    }

    override fun notice(text: String) {
        toastLong(text)
    }

    override fun linkEnded(title: String, message: String) {
        val retry = session.retryInvite
        val back = if (title.startsWith("Lost the link") && gameReady && autosave.beforeLinkFile() != null)
            "Go back to before the link" to { goBackToBeforeLink() } else null
        dialogs.alert(title, message, retry = retry?.let { inv -> "Try again" to { joinInvite(inv) } }, neutral = back)
    }

    override fun showGameScreen() = showScreen(Screen.GAME)

    override fun playerName(): String = Settings.playerName

    override fun partnerLeft(name: String) {
        flashStatus("$name left the link", 6000)
        if (screen == Screen.GAME) softResetDialog("$name left the link", "If the game is stuck waiting for them, soft reset.")
        else toastLong("$name left the link. If the game is stuck waiting for them, soft reset.")
    }

    // ---- for the snapshot run and the menu bar ---------------------------------------------------------

    fun dispose() {
        ticker.stop()
    }

    fun softReset() = softResetDialog()

    /** Pretends a game is running and shows [frame] on the game screen. */
    internal fun demoGame(frame: IntArray) {
        gameReady = true
        showScreen(Screen.GAME)
        gameView.chipsAlwaysOn = true
        gameView.offerFrame(frame)
    }

    internal fun menuFilterPage() = menuFilter()
    internal fun menuVolumePage() = menuVolume()
    internal fun menuStatesPage(save: Boolean) = menuStates(save)
    internal fun replyDialogDemo(problem: String?) = replyDialog(problem)

    // ---- quitting ---------------------------------------------------------------------------------------

    private var quitModal: Modal? = null

    /**
     * The one way out: window close, Cmd+Q, Dock Quit. A room with someone else in it is asked about first (the close
     * button is easy to hit, and quitting ends the link for everyone). [confirm] false: no question (the development
     * switch `-Dlinkplay.autoquit`). Returns at once when it asks: the dialog's Quit button carries on with [quit].
     */
    fun requestQuit(confirm: Boolean = true) {
        if (quitting) return
        if (confirm) {
            if (quitModal != null && overlay.current === quitModal) return // the question is already up
            // The same test as the Close room button: a host alone in the room may leave without a question.
            val hosting = session.link?.hosting == true
            if (if (hosting) session.closeNeedsConfirm else session.inRoom) {
                quitModal = dialogs.confirm("Quit Link Play?",
                    if (hosting) "This closes your room. Everyone else in it loses the link." else "This leaves your room.",
                    "Quit", destructive = true) { quit() }
                return
            }
        }
        quit()
    }

    /**
     * Order matters: the last snapshot first (before the link ends and before the autosave thread stops), then the room
     * says goodbye (its BYE and the router's port), then the core stops and the files are flushed. The slow part runs
     * on its own thread so the window can still show "Closing…".
     */
    private fun quit() {
        if (quitting) return
        rememberName()
        keys.releaseAll()
        val snap = gameReady && !session.blocksAutosave
        if (gameReady) saveScene()
        quitting = true
        val card = ShadowCard(24f, Insets(22, 28, 22, 28), 6)
        card.put(TextBlock("Closing Link Play…", Fonts.bold(16f), Pal.ink, align = javax.swing.SwingConstants.CENTER))
        card.put(TextBlock(if (snap) "Saving your game first." else "Just a moment.", Fonts.regular(13.5f), Pal.muted, align = javax.swing.SwingConstants.CENTER), 4)
        overlay.show(Modal(card, width = 320, cancelable = false))
        root.paintImmediately(0, 0, root.width, root.height)
        Thread({
            try {
                if (snap) autosave.snapshotNow(wait = true)
                SwingUtilities.invokeAndWait { session.shutdown() }
                val clean = session.awaitShutdown(3000)
                Log.i("quit: link closed ${if (clean) "cleanly" else "late"}")
                autosave.shutdown(wait = true)
                emu.stop()
                Settings.save()
            } catch (t: Throwable) {
                Log.e("quit", t)
            }
            Log.i("quit: done")
            Log.close()
            try { SwingUtilities.invokeAndWait { win.closeWindow() } } catch (_: Throwable) {}
            kotlin.system.exitProcess(0)
        }, "quit").start()
    }

    companion object {
        /** The biggest file taken for a save (the same ceiling as RomStore.importSave); real saves are 512 B to 128 KiB. */
        private const val MAX_SAVE_BYTES = 256L shl 10
        const val NO_SOUND = "No sound device found: the game plays without sound."
        const val SOLO = "Solo · host or join a game from the lobby (Esc)"
        val FILTERS = arrayOf("Pixel", "Sharp", "Smooth")
        val FILTER_INFO = arrayOf("Original pixels", "Crisp pixels, smooth scaling", "Soft and blurry")
    }
}
