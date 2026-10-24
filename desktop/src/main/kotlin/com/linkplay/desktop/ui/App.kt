package com.linkplay.desktop.ui

import com.linkplay.desktop.AppInfo
import com.linkplay.desktop.Emulator
import com.linkplay.desktop.Log
import com.linkplay.desktop.Settings
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.MenuEvent
import javax.swing.event.MenuListener
import kotlin.math.max
import kotlin.math.min

/**
 * How big the window may be: its wanted size for a scale, held inside what the screen has room for (a scaled Windows
 * laptop is 1280x720 or less, minus the taskbar). Plain numbers in and out, so it can be checked without a screen.
 */
object WindowFit {
    /** Height kept free of the usable area for the title bar and the window frame, which the area does not count. */
    const val CHROME_H = 40
    const val MIN_W = 860
    const val MIN_H = 600

    /** What the window would like to be at [scale]: the lobby's 1000x700 up to the picture's whole-number size. */
    fun wanted(scale: Int) = Dimension(max(1000, 240 * scale + 40), max(700, 160 * scale + 104 + 28 + 20))

    /** The room a window may take: [usable] (the screen less the taskbar or menu bar and Dock), less the chrome. */
    fun room(usable: Rectangle) = Dimension(max(1, usable.width), max(1, usable.height - CHROME_H))

    fun size(scale: Int, usable: Rectangle): Dimension {
        val w = wanted(scale)
        val r = room(usable)
        return Dimension(min(w.width, r.width), min(w.height, r.height))
    }

    fun minimum(usable: Rectangle): Dimension {
        val r = room(usable)
        return Dimension(min(MIN_W, r.width), min(MIN_H, r.height))
    }

    /** [b] moved (not resized) to lie inside [usable]; as much of it as fits when it is bigger. */
    fun inside(b: Rectangle, usable: Rectangle): Rectangle {
        val x = max(usable.x, min(b.x, usable.x + usable.width - b.width))
        val y = max(usable.y, min(b.y, usable.y + usable.height - b.height))
        return Rectangle(x, y, b.width, b.height)
    }

    /** The screen's usable area: its bounds less the taskbar, menu bar and Dock. [gc] null: the main screen. */
    fun usable(gc: GraphicsConfiguration?): Rectangle = try {
        val cfg = gc ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val b = cfg.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(cfg)
        Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    } catch (e: Exception) {
        try { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds } catch (e2: Exception) { Rectangle(0, 0, 1920, 1080) }
    }
}

/**
 * The window. [start] is called by Main when no command line mode was asked for: the data folder, the log and the
 * settings are ready, and this process owns the data folder.
 *
 * Two development switches for trying the real window without a person: `-Dlinkplay.autoplay=true` starts the game a
 * moment after the window opens, `-Dlinkplay.autoquit=<seconds>` quits the way the window's close button does.
 */
object App {
    fun start(args: Array<String>) {
        // Before the first AWT class loads: a dark title bar and dark system dialogs, to match the app.
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
        SwingUtilities.invokeLater {
            try {
                open()
            } catch (t: Throwable) {
                Log.e("window", t)
                JOptionPane.showMessageDialog(null, "Link Play could not open its window.\n${t.message ?: t.javaClass.simpleName}", AppInfo.NAME, JOptionPane.ERROR_MESSAGE)
                System.exit(1)
            }
        }
    }

    fun lookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            Log.w("look and feel: ${e.message}")
        }
    }

    private fun open() {
        lookAndFeel()
        Emulator.nativeProblem()?.let {
            JOptionPane.showMessageDialog(null, "Link Play could not load its game engine.\n$it\n\nTry installing the app again.", AppInfo.NAME, JOptionPane.ERROR_MESSAGE)
            System.exit(1)
        }
        val shell = Shell(Emulator())
        val frame = AppFrame(shell)
        shell.win = frame
        shell.applyChrome()
        frame.open()
    }
}

class AppFrame(private val shell: Shell) : JFrame(AppInfo.NAME), WindowOps {
    override val frame: Frame get() = this
    private var full = false
    override val isFullScreen get() = full
    private var windowed: Rectangle? = null

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        contentPane = shell.root
        background = Pal.bg
        minimumSize = WindowFit.minimum(usableArea())
        val icons = listOf(32, 64, 128, 256, 512).mapNotNull { n ->
            try { AppFrame::class.java.getResourceAsStream("/icons/linkplay-$n.png")?.use { ImageIO.read(it) } } catch (e: Exception) { null }
        }
        if (icons.isNotEmpty()) iconImages = icons
        if (Fonts.mac) {
            // The content runs under a see-through title bar: one dark window, like a Mac app.
            rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE) && icons.isNotEmpty()) {
                Taskbar.getTaskbar().iconImage = icons.last()
            }
        } catch (e: Exception) {
            Log.w("dock icon: ${e.message}")
        }
        size = sizeFor(Settings.scale)
        setLocationRelativeTo(null)
        keepOnScreen()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = shell.requestQuit()
            override fun windowIconified(e: WindowEvent) = shell.windowMinimised(true)
            override fun windowDeiconified(e: WindowEvent) = shell.windowMinimised(false)
        })
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) = shell.windowActivated(true)
            override fun windowLostFocus(e: WindowEvent) = shell.windowActivated(false)
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { shell.dispatchKey(it) }
        DropTarget(shell.root, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun drop(e: DropTargetDropEvent) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    e.dropComplete(true)
                    files.firstOrNull()?.let { f -> SwingUtilities.invokeLater { shell.dropped(f) } }
                } catch (ex: Exception) {
                    e.dropComplete(false)
                }
            }
        })
        if (Fonts.mac) {
            jMenuBar = buildMenuBar()
        }
        installSystemHandlers()
    }

    private fun usableArea() = WindowFit.usable(graphicsConfiguration)

    private fun sizeFor(scale: Int): Dimension = WindowFit.size(scale, usableArea())

    /** Moves the window, if need be, so that all of it lies inside the screen's usable area (not under the taskbar). */
    private fun keepOnScreen() {
        val fitted = WindowFit.inside(bounds, usableArea())
        if (fitted != bounds) bounds = fitted
    }

    private fun installSystemHandlers() {
        try {
            if (!Desktop.isDesktopSupported()) return
            val d = Desktop.getDesktop()
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                // Cmd+Q and Dock > Quit: the same way out as the close button. We exit ourselves when it is done.
                d.setQuitHandler { _, response ->
                    shell.requestQuit()
                    response.cancelQuit()
                }
            }
            if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler { shell.about() }
        } catch (e: Exception) {
            Log.w("system handlers: ${e.message}")
        }
    }

    fun open() {
        isVisible = true
        if (Settings.fullscreen) SwingUtilities.invokeLater { setFullScreen(true) }
        System.getProperty("linkplay.autoplay")?.let { Timer(1500) { shell.play() }.apply { isRepeats = false; start() } }
        if (System.getProperty("linkplay.selfdrive") != null) selfDrive()
        System.getProperty("linkplay.autoquit")?.toIntOrNull()?.let { s -> Timer(s * 1000) { shell.requestQuit(confirm = false) }.apply { isRepeats = false; start() } }
    }

    /**
     * `-Dlinkplay.selfdrive=true` (with the built-in test ROM in the data folder): plays it for a few seconds with synthetic
     * key events and logs PASS or FAIL lines ("selfdrive: ..."), then paints the real window into `-Dlinkplay.shot=<file>`.
     * A way to see the real window, game, keyboard and menu work together without a person at the keys.
     */
    private fun selfDrive() {
        fun say(ok: Boolean, what: String) = Log.i("selfdrive: ${if (ok) "PASS" else "FAIL"} $what")
        fun key(id: Int, code: Int) = shell.dispatchKey(KeyEvent(shell.gameView, id, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
        val steps = listOf<() -> Unit>(
            { say(shell.screen == Shell.Screen.GAME, "the game screen is up after Play"); say(shell.gameView.hasFrame, "pictures arrive") },
            { key(KeyEvent.KEY_PRESSED, KeyEvent.VK_X) },
            { say(shell.gameView.centerPixel() == com.linkplay.desktop.TestRom.GREEN_RGB, "key X is A: the picture turns green"); key(KeyEvent.KEY_RELEASED, KeyEvent.VK_X); key(KeyEvent.KEY_PRESSED, KeyEvent.VK_Z) },
            { say(shell.gameView.centerPixel() == com.linkplay.desktop.TestRom.BLUE_RGB, "key Z is B: the picture turns blue"); key(KeyEvent.KEY_RELEASED, KeyEvent.VK_Z); key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE); key(KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE) },
            { say(shell.overlay.isOpen, "Escape opens the game menu"); say(shell.emu.isPaused, "the game pauses behind the menu") },
            { shot(); shell.overlay.dismiss() },
            { say(!shell.overlay.isOpen, "the menu closes"); say(!shell.emu.isPaused, "the game runs again") },
            { shot("-game") },
        )
        var i = 0
        val t = Timer(700) {
            if (i < steps.size) steps[i++]() else (it.source as Timer).stop()
        }
        t.initialDelay = 3200
        t.start()
    }

    private fun shot(suffix: String = "") {
        val base = System.getProperty("linkplay.shot") ?: return
        try {
            val img = java.awt.image.BufferedImage(shell.root.width, shell.root.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            shell.root.paint(g)
            g.dispose()
            val f = File(base.removeSuffix(".png") + suffix.ifEmpty { "-menu" } + ".png")
            ImageIO.write(img, "png", f)
            Log.i("selfdrive: picture of the window written (${f.name})")
        } catch (e: Exception) {
            Log.w("selfdrive: picture failed: ${e.message}")
        }
    }

    override fun resizeForScale(): Boolean {
        if (full) return false
        val before = bounds
        val want = sizeFor(Settings.scale)
        // Grow and shrink around the middle of the window, then back onto the screen.
        setBounds(before.x + (before.width - want.width) / 2, before.y + (before.height - want.height) / 2, want.width, want.height)
        keepOnScreen()
        minimumSize = WindowFit.minimum(usableArea())
        return size != before.size
    }

    override fun setFullScreen(on: Boolean) {
        if (on == full) return
        val dev = graphicsConfiguration?.device ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        if (on && !dev.isFullScreenSupported) {
            shell.toast("Full screen isn't available on this screen.")
            return
        }
        if (on) {
            windowed = bounds
            dispose()
            isUndecorated = true
            full = true
            dev.fullScreenWindow = this
        } else {
            dev.fullScreenWindow = null
            dispose()
            isUndecorated = false
            full = false
            windowed?.let { bounds = it }
            isVisible = true
        }
        Settings.fullscreen = on
        shell.applyChrome()
        requestFocus()
    }

    override fun closeWindow() {
        try {
            val dev = graphicsConfiguration?.device
            if (full && dev != null) dev.fullScreenWindow = null
        } catch (_: Exception) {}
        dispose()
    }

    // ---- the Mac menu bar ----------------------------------------------------------------------

    private fun buildMenuBar(): JMenuBar {
        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun item(text: String, key: Int? = null, extra: Int = 0, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
            if (key != null) accelerator = KeyStroke.getKeyStroke(key, mask or extra)
            addActionListener { if (!shell.overlay.isOpen && !shell.quitting) action() }
        }
        val bar = JMenuBar()

        val file = JMenu("File")
        file.add(item("Choose ROM…", KeyEvent.VK_O) { shell.chooseRom() })
        file.add(item("Import Save…") { shell.importSave() })
        file.add(item("Export Save…") { shell.exportSave() })
        bar.add(file)

        val game = JMenu("Game")
        val host = item("Host a Game") { shell.hostGame() }
        val join = item("Join a Game…", KeyEvent.VK_J) { shell.joinGame() }
        val menu = item("Game Menu") { shell.gameMenu() }
        val saves = JMenu("Save State")
        val loads = JMenu("Load State")
        for (n in 1..3) {
            saves.add(item("Slot $n") { shell.saveSlot(n) })
            loads.add(item("Slot $n") { shell.loadSlot(n) })
        }
        val reset = item("Soft Reset…") { shell.softReset() }
        val lobby = item("Back to Lobby", KeyEvent.VK_L) { shell.showScreen(Shell.Screen.HOME) }
        for (c in listOf(host, join)) game.add(c)
        game.addSeparator()
        game.add(menu)
        game.add(saves)
        game.add(loads)
        game.addSeparator()
        game.add(reset)
        game.add(lobby)
        game.addMenuListener(object : MenuListener {
            override fun menuSelected(e: MenuEvent) {
                val inGame = shell.screen == Shell.Screen.GAME && shell.gameReady
                host.isEnabled = shell.rom != null
                join.isEnabled = shell.rom != null
                menu.isEnabled = inGame
                saves.isEnabled = inGame
                loads.isEnabled = inGame
                reset.isEnabled = inGame
                lobby.isEnabled = shell.screen != Shell.Screen.HOME && shell.screen != Shell.Screen.SETUP
            }
            override fun menuDeselected(e: MenuEvent) {}
            override fun menuCanceled(e: MenuEvent) {}
        })
        bar.add(game)

        val view = JMenu("View")
        view.add(item("Full Screen", KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK) { shell.toggleFullScreen() })
        view.add(item("Zoom In", KeyEvent.VK_EQUALS) { shell.zoom(1) })
        view.add(item("Zoom Out", KeyEvent.VK_MINUS) { shell.zoom(-1) })
        view.addSeparator()
        val filters = Shell.FILTERS.mapIndexed { i, name ->
            JRadioButtonMenuItem(name).apply { addActionListener { Settings.filter = i; shell.gameView.repaint() } }
        }
        for (f in filters) view.add(f)
        view.addMenuListener(object : MenuListener {
            override fun menuSelected(e: MenuEvent) { filters.forEachIndexed { i, f -> f.isSelected = i == Settings.filter } }
            override fun menuDeselected(e: MenuEvent) {}
            override fun menuCanceled(e: MenuEvent) {}
        })
        bar.add(view)
        return bar
    }
}
