package com.linkplay.desktop

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import com.linkplay.app.net.CodeError
import com.linkplay.app.net.Codes
import com.linkplay.app.net.Invite
import com.linkplay.app.net.Reply

/**
 * The link play test tool: the same room code as the window, without a window. Run two copies (each with
 * its own `--data` folder) on one computer, or one next to a phone, to check that a room opens, a player
 * joins, the cable attaches and the game keeps its pace.
 *
 *     --cli-host <rom> [--seconds N] [--wait N] [--linger N] [--name X] [--stun]
 *     --cli-join <rom> <codeFile|-> [--seconds N] [--wait N] [--linger N] [--name X] [--stun] [--print-reply]
 *
 * It loads the ROM (silently: no sound device is needed), then:
 *  - host: opens a room and prints `INVITE <code>` on standard output (a local test tool: the code goes to
 *    the operator's own terminal, never to the log); write that to a file for the guest;
 *  - guest: reads the invite from the file (or `-` for standard input) and joins; `--print-reply` also prints
 *    `REPLY <code>` (the reply code, for the host to paste when the invite alone does not get through).
 *
 * Both then wait up to `--wait` seconds (default 120) for the cable to be attached with two players in the
 * room, print `LINKED players=<k> id=<n>`, run `--seconds` more (default 20) while measuring the emulation
 * speed, print the lines below, stay in the room `--linger` more seconds (default 0: so the other side can take
 * its own measurement before this one leaves), and exit:
 *
 *     STATS frames=<frames emulated in that window> linked=<cable in and 2+ players> players=<k> fps=<x>
 *     LINKSTATS pushed=<frames the room gave our core> polled=<bytes our core gave the room>
 *
 * Other lines: `NOTICE ...` (what the window would show as a toast), `PARTNER_LEFT <name>`, `ENDED <title>: <why>`
 * (the room was lost or could not start), `CLOSED clean=<true|false>` (the goodbyes went out). All status
 * output goes to standard output; the log (with the room's own lines, e.g. the BYE) goes to standard error
 * and to log.txt in the data folder.
 *
 * Exit code: 0 after a run that linked (even when the room ended meanwhile: that is reported, not a failure),
 * 2 for a bad command line, ROM or code, 4 when nobody linked within `--wait`, 5 when the game or the room
 * could not start.
 */
object Cli {
    private class Args(
        val host: Boolean,
        val rom: File,
        val codeFile: String?,
        val seconds: Int,
        val wait: Int,
        val linger: Int,
        val name: String,
        val stun: Boolean,
        val printReply: Boolean,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val a = parse(args) ?: exitWith(2)
        val code = try {
            run(a)
        } finally {
            System.out.flush()
        }
        if (code != 0) exitProcess(code)
    }

    private fun parse(args: Array<String>): Args? {
        var host = false
        var join = false
        var rom: String? = null
        var codeFile: String? = null
        var seconds = 20
        var wait = 120
        var linger = 0
        var name: String? = null
        var stun = false
        var printReply = false
        var i = 0
        fun value(flag: String): String? = args.getOrNull(++i).also { if (it == null) println("ERROR $flag needs a value") }
        while (i < args.size) {
            when (val s = args[i]) {
                "--cli-host" -> { host = true; rom = value(s) ?: return null }
                "--cli-join" -> { join = true; rom = value(s) ?: return null; codeFile = value(s) ?: return null }
                "--seconds" -> seconds = value(s)?.toIntOrNull() ?: return null
                "--wait" -> wait = value(s)?.toIntOrNull()?.takeIf { it in 1..3600 } ?: run { println("ERROR --wait needs 1 to 3600"); return null }
                "--linger" -> linger = value(s)?.toIntOrNull()?.takeIf { it in 0..600 } ?: run { println("ERROR --linger needs 0 to 600"); return null }
                "--name" -> name = value(s) ?: return null
                "--data" -> value(s) ?: return null // Main has used it already
                "--stun" -> stun = true
                "--print-reply" -> printReply = true
            }
            i++
        }
        if (host == join) { // neither, or both
            println("ERROR use either --cli-host <rom> or --cli-join <rom> <codeFile|->")
            return null
        }
        return Args(host, File(rom!!), codeFile, seconds, wait, linger, name ?: if (host) "CliHost" else "CliGuest", stun, printReply)
    }

    private fun exitWith(code: Int): Nothing {
        System.out.flush()
        exitProcess(code)
    }

    private fun run(a: Args): Int {
        // The invite first: a bad code or ROM should fail before any game is loaded.
        val invite: Invite? = if (a.host) null else {
            val text = try {
                if (a.codeFile == "-") String(System.`in`.readBytes(), Charsets.UTF_8) else File(a.codeFile!!).readText()
            } catch (e: Exception) {
                println("ERROR cannot read the code: ${e.message}")
                return 2
            }
            when (val r = Codes.find(text, null)) {
                is Invite -> r
                is Reply -> { println("ERROR that is a reply code, the guest needs the host's invite"); return 2 }
                is CodeError -> { println("ERROR ${r.message}"); return 2 }
            }
        }
        val info = try {
            RomStore.import(a.rom)
        } catch (e: IllegalArgumentException) {
            println("ERROR ${e.message}")
            return 2
        }
        if (invite != null && !invite.romMatches(info.sha1)) {
            println("ERROR the invite is for a different ROM than ${a.rom.name}")
            return 2
        }

        val emu = Emulator(withAudio = false)
        val autosave = AutoSave(emu) { info.sha1 }
        val host = CliHost(autosave, a.name)
        val session = LinkSession(host, emu, { info }, { a.stun })
        if (!emu.load(info.file, RomStore.saveFile(info))) {
            println("ERROR the game could not start")
            return 5
        }
        emu.start()
        emu.setPaused(true) // like the window: the game waits (on its title screen) until the cable goes in
        autosave.booted()
        host.loaded = true
        println("GAME ${info.title} ${info.shortHash}")

        var exit = 0
        try {
            exit = linkAndMeasure(a, invite, emu, session, host)
        } finally {
            onEdt { session.shutdown() }
            val clean = session.awaitShutdown(3000)
            println("CLOSED clean=$clean")
            autosave.shutdown(wait = true)
            emu.stop()
        }
        return exit
    }

    private fun linkAndMeasure(a: Args, invite: Invite?, emu: Emulator, session: LinkSession, host: CliHost): Int {
        val begin = AtomicReference<LinkSession.Begin>()
        onEdt { begin.set(if (invite == null) session.hostGame() else session.joinInvite(invite)) }
        if (begin.get() != LinkSession.Begin.STARTING) {
            println("ERROR could not start the room: ${begin.get()}")
            return 5
        }

        val printed = Printed()
        fun pump() = printCodes(a, session, printed)
        fun linked() = session.linkAttached && session.roster.size >= 2

        val waitUntil = System.nanoTime() + a.wait * 1_000_000_000L
        while (!linked() && host.ended == null && System.nanoTime() < waitUntil) {
            pump()
            sleep(50)
        }
        pump()
        if (!linked()) {
            // The room failed (ENDED already printed) or nobody came in time.
            println("STATS frames=0 linked=false players=${session.roster.size} fps=0.0")
            if (host.ended == null) println("ERROR nobody linked within ${a.wait} s")
            return if (host.ended != null) 5 else 4
        }
        println("LINKED players=${session.roster.size} id=${session.linkId}")

        val f0 = emu.frameCount()
        val t0 = System.nanoTime()
        val until = t0 + a.seconds * 1_000_000_000L
        while (System.nanoTime() < until && host.ended == null) {
            pump()
            sleep(50)
        }
        val t1 = System.nanoTime()
        val frames = emu.frameCount() - f0
        val secs = (t1 - t0) / 1e9
        val fps = if (secs > 0) frames / secs else 0.0
        println("STATS frames=$frames linked=${linked()} players=${session.roster.size} fps=${"%.1f".format(java.util.Locale.US, fps)}")
        println("LINKSTATS pushed=${session.framesPushed} polled=${session.bytesPolled}")
        // Stay in the room a little longer, so the other side's measurement ends before we leave.
        val lingerUntil = System.nanoTime() + a.linger * 1_000_000_000L
        while (System.nanoTime() < lingerUntil && host.ended == null) sleep(50)
        return 0
    }

    private class Printed {
        var invite = ""
        var reply = ""
    }

    /** Prints the invite (host) or, with --print-reply, the reply code (guest) when there is a new one. */
    private fun printCodes(a: Args, session: LinkSession, printed: Printed) {
        val l = session.link ?: return
        if (l.code.isEmpty() || l.offline) return
        if (l.hosting && l.code != printed.invite) {
            printed.invite = l.code
            println("INVITE ${l.code}")
        } else if (!l.hosting && a.printReply && l.code != printed.reply) {
            printed.reply = l.code
            println("REPLY ${l.code}")
        }
        System.out.flush()
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    /** The window's part, headless: the game is already loaded, the snapshot is taken at once, messages are printed. */
    private class CliHost(private val autosave: AutoSave, private val name: String) : LinkHost {
        @Volatile var loaded = false
        @Volatile var ended: String? = null

        override val gameLoaded: Boolean get() = loaded

        override fun ensureGameLoaded(onReady: () -> Unit) = onReady()

        override fun beforeLinkSnapshot(done: () -> Unit) {
            autosave.snapshot(AutoSave.Kind.BEFORE_LINK) { SwingUtilities.invokeLater(done) }
        }

        override fun linkChanged() {}

        override fun notice(text: String) = println("NOTICE $text")

        override fun linkEnded(title: String, message: String) {
            ended = "$title: $message"
            println("ENDED $title: $message")
        }

        override fun showGameScreen() {}

        override fun playerName() = name

        override fun partnerLeft(name: String) = println("PARTNER_LEFT $name")
    }
}
