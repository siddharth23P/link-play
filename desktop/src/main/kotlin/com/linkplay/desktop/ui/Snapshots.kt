package com.linkplay.desktop.ui

import com.linkplay.app.net.Candidate
import com.linkplay.app.net.Codes
import com.linkplay.app.net.RomId
import com.linkplay.desktop.AppDirs
import com.linkplay.desktop.Emulator
import com.linkplay.desktop.Log
import com.linkplay.desktop.RomStore
import com.linkplay.desktop.Settings
import com.linkplay.desktop.TestRom
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * `--ui-snapshots <dir>`: draws every screen into a PNG (1100 by 760) without opening a window, so the look can be
 * reviewed and checked from a script. The app's own screens and dialogs are used, fed with made-up state (no network,
 * no real game): a built-in test ROM, a drawn picture, an invite code made on the spot for a room that does not exist.
 * Also runs the keyboard rules against a fake game. Writes `report.txt` (PASS or FAIL lines) next to the pictures.
 */
object Snapshots {
    const val W = 1100
    const val H = 760

    fun run(dir: File): Int {
        dir.mkdirs()
        val report = ArrayList<String>()
        var error: Throwable? = null
        try {
            SwingUtilities.invokeAndWait {
                try { scenes(dir, report) } catch (t: Throwable) { error = t }
            }
        } catch (t: Throwable) {
            error = t
        }
        error?.let {
            Log.e("ui snapshots", it)
            report += "FAIL exception: $it"
        }
        report += if (report.none { it.startsWith("FAIL") }) "DONE ${report.count { it.startsWith("PASS") }} checks passed" else "DONE with failures"
        File(dir, "report.txt").writeText(report.joinToString("\n") + "\n")
        report.forEach { println(it) }
        return if (report.any { it.startsWith("FAIL") }) 1 else 0
    }

    private fun layoutAll(c: Component) {
        if (c is Container) {
            c.doLayout()
            for (k in c.components) layoutAll(k)
        }
    }

    private fun render(shell: Shell, dir: File, name: String, report: MutableList<String>, w: Int = W, h: Int = H) {
        val root = shell.root
        root.setSize(w, h)
        repeat(3) { layoutAll(root) }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        root.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(dir, "$name.png"))
        report += "PASS drew $name"
    }

    private fun prepare(shell: Shell) {
        shell.root.setSize(W, H)
        repeat(3) { layoutAll(shell.root) }
    }

    private fun scenes(dir: File, report: MutableList<String>) {
        App.lookAndFeel()
        Settings.filter = Settings.FILTER_SHARP
        val emu = Emulator(withAudio = false)

        // First start: no ROM yet.
        val first = Shell(emu)
        render(first, dir, "01-setup", report)
        first.dispose()

        // A ROM and a last scene, as after a game of play.
        val romFile = File(AppDirs.root, "demo.gba")
        TestRom.write(romFile)
        val info = RomStore.import(romFile)
        val frame = SyntheticFrame.make()
        val img = BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 240, 160, frame, 0, 240)
        val states = RomStore.statesDir(info.sha1).apply { mkdirs() }
        ImageIO.write(img, "png", File(states, "last-frame.png"))
        Settings.name = "Ash"

        val shell = Shell(emu)
        shell.home.showName("Ash")
        render(shell, dir, "02-home", report)
        shell.home.showAdvanced(true)
        render(shell, dir, "02b-home-advanced", report)
        render(shell, dir, "02d-home-smallest-window", report, 860, 600) // the smallest the window can be: it scrolls
        shell.home.showAdvanced(false)

        // Hosting: the lobby shows the room.
        shell.demoHome = com.linkplay.desktop.ui.HomeModel(
            romTitle = info.title, thumb = img, sub = "In Room \"Ash's room\" · P1 Ash · P2 Bo", primary = "Return to game", primaryEnabled = true,
            fromSave = false, linkMode = 1, status = "Hosting Ash's room · 2/4 players", netInfo = "")
        shell.refreshHome()
        render(shell, dir, "02c-home-hosting", report)
        shell.demoHome = null
        shell.refreshHome()

        // The picture, with each filter.
        shell.demoGame(frame)
        prepare(shell)
        shell.toasts.showStatic("Saved to slot 2")
        render(shell, dir, "03-game", report)
        shell.toasts.showStatic("")
        Settings.filter = Settings.FILTER_PIXEL
        render(shell, dir, "03b-game-pixel", report)
        Settings.filter = Settings.FILTER_SMOOTH
        render(shell, dir, "03c-game-smooth", report)
        Settings.filter = Settings.FILTER_SHARP

        // The game menu and its pages.
        shell.gameMenu()
        render(shell, dir, "06-game-menu", report)
        shell.menuFilterPage()
        render(shell, dir, "06b-menu-screen", report)
        shell.menuVolumePage()
        render(shell, dir, "06c-menu-volume", report)
        shell.menuStatesPage(true)
        render(shell, dir, "06d-menu-states", report)
        shell.overlay.dismiss()

        // Dialogs over the picture.
        shell.controlsDialog()
        render(shell, dir, "07-controls", report)
        shell.overlay.dismiss()
        shell.replyDialogDemo(null)
        render(shell, dir, "08-paste-reply", report)
        shell.overlay.dismiss()
        shell.replyDialogDemo("That's an invite to someone's room, not a reply code. Your friend's reply code starts with LP1R-.")
        render(shell, dir, "08b-paste-reply-problem", report)
        shell.overlay.dismiss()
        shell.joinGame()
        render(shell, dir, "09-join", report)
        shell.overlay.dismiss()
        shell.softReset()
        render(shell, dir, "10-soft-reset", report)
        shell.overlay.dismiss()
        shell.about()
        render(shell, dir, "11-about", report)
        shell.overlay.dismiss()

        // The link screens, with a made-up room.
        val rom = RomId(info.sha1, info.title, info.code, info.size)
        val code = Codes.newInvite("Ash", "Ash's room", rom, 4, listOf(
            Candidate(InetSocketAddress(InetAddress.getByName("192.168.1.23"), 47001), Candidate.Kind.LAN),
            Candidate(InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:9abc:def0:1234:5678"), 47001), Candidate.Kind.IPV6),
        )).encode()
        shell.demoInvite = InviteModel(
            title = "Your room is open", code = code, codeReady = true, left = "P1 Ash", right = "P2 Bo", linked = true,
            players = "2/4 players", note = "Bo connected directly",
            info = "Link uses Wi-Fi (IPv6)\nReachable via: IPv6 ✓ · Wi-Fi LAN · router port ✓\n" +
                "Tip: playing with 3 or 4 in different places? Everyone connects through the host, so the player with IPv6 should host.",
        )
        shell.showScreen(Shell.Screen.INVITE)
        render(shell, dir, "04-invite", report)
        shell.demoInvite = InviteModel(
            title = "Opening your room…", code = "Asking your router to open a port…", codeReady = false, left = "P1 Ash", right = null, linked = false,
            players = "1/4 players", note = "", info = "")
        shell.showScreen(Shell.Screen.INVITE)
        render(shell, dir, "04b-invite-opening", report)
        shell.demoInvite = null

        shell.demoJoining = JoiningModel(
            title = "Connecting to Ash's room…", left = "P1 Ash", right = "You · Bo", linked = false,
            info = "Trying 2 addresses (LAN, IPv6)\nLink uses Wi-Fi (IPv6)",
            prompt = "No answer from Ash yet. Send Ash your reply code now.", promptStyle = Prompt.LOUD_BAD,
            code = Codes.newInvite("Bo", "x", rom, 2, emptyList()).encode().replace("LP1I-", "LP1R-"), codeReady = true, codeVisible = true, note = "")
        shell.showScreen(Shell.Screen.JOINING)
        render(shell, dir, "05-joining", report)
        shell.demoJoining = JoiningModel(
            title = "Connected directly", left = "P1 Ash", right = "P2 Bo", linked = true,
            info = "Joining Ash's room…\nLink uses Wi-Fi (IPv6)", prompt = "", promptStyle = Prompt.NONE,
            code = "", codeReady = false, codeVisible = false, note = "")
        shell.showScreen(Shell.Screen.JOINING)
        render(shell, dir, "05b-joining-connected", report)
        shell.demoJoining = null

        shell.dispose()

        for (line in KeyRouter.selfCheck()) report += line
    }
}

/** A made-up 240 by 160 picture that looks a little like a game: for the screenshots, never for the game. */
object SyntheticFrame {
    fun make(): IntArray {
        val img = BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        for (y in 0 until 72) {
            g.color = Pal.mix(Color(0x4FA8E8), Color(0xC6E6F8), y / 72f)
            g.fillRect(0, y, 240, 1)
        }
        g.color = Color(0xFFE08A); g.fillOval(190, 10, 22, 22)
        g.color = Color(0xFFFFFF); g.fillRect(40, 20, 34, 6); g.fillRect(46, 15, 22, 6); g.fillRect(120, 34, 28, 5); g.fillRect(126, 30, 16, 5)
        g.color = Color(0x4C9A5B)
        g.fillPolygon(intArrayOf(0, 40, 80, 120, 0), intArrayOf(72, 40, 72, 72, 72), 4)
        g.fillPolygon(intArrayOf(100, 160, 220, 240, 240, 100), intArrayOf(72, 44, 72, 66, 72, 72), 6)
        for (ty in 0 until 6) for (tx in 0 until 15) {
            g.color = if ((tx + ty) % 2 == 0) Color(0x5DB86B) else Color(0x54AB62)
            g.fillRect(tx * 16, 72 + ty * 16, 16, 16)
        }
        g.color = Color(0xD9C48B); g.fillRect(0, 112, 240, 16)
        g.color = Color(0xC4AE76)
        for (x in 0 until 240 step 16) g.fillRect(x + 4, 118, 8, 2)
        // A house.
        g.color = Color(0xB5453A); g.fillPolygon(intArrayOf(150, 178, 206), intArrayOf(72, 50, 72), 3)
        g.color = Color(0xF1E3C8); g.fillRect(156, 72, 44, 32)
        g.color = Color(0x6B4B2B); g.fillRect(172, 84, 12, 20)
        g.color = Color(0x7CC4F0); g.fillRect(162, 78, 8, 8); g.fillRect(188, 78, 8, 8)
        // Trees.
        for (x in intArrayOf(14, 48, 96)) {
            g.color = Color(0x6B4B2B); g.fillRect(x + 6, 90, 4, 12)
            g.color = Color(0x2F7A44); g.fillOval(x, 70, 16, 24)
        }
        // The player.
        g.color = Color(0x3856C8); g.fillRect(112, 100, 12, 10)
        g.color = Color(0xF2C9A0); g.fillRect(113, 91, 10, 9)
        g.color = Color(0xD6455D); g.fillRect(111, 87, 14, 5); g.fillRect(114, 84, 8, 4)
        g.color = Color(0x222222); g.fillRect(115, 95, 2, 2); g.fillRect(120, 95, 2, 2); g.fillRect(113, 110, 4, 4); g.fillRect(120, 110, 4, 4)
        // A message box.
        g.color = Color(0x1E1E2A); g.fillRoundRect(8, 128, 224, 28, 6, 6)
        g.color = Color(0xFFFFFF); g.fillRoundRect(10, 130, 220, 24, 4, 4)
        g.color = Color(0x2A2A3A)
        g.fillRect(18, 137, 120, 3); g.fillRect(18, 144, 86, 3); g.fillRect(205, 146, 6, 3)
        g.dispose()
        return img.getRGB(0, 0, 240, 160, null, 0, 240)
    }
}
