package com.linkplay.desktop

import java.awt.GraphicsEnvironment
import java.io.File
import java.util.Locale
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Name and version of the app. The packaged app gets its version from jpackage (it sets the
 * `jpackage.app-version` property from `version` in desktop/build.gradle.kts); DEV_VERSION is what a
 * run from Gradle or the IDE shows, keep it in step.
 */
object AppInfo {
    const val NAME = "Link Play"
    const val DEV_VERSION = "1.1.0"
    val VERSION: String = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: DEV_VERSION
}

private val USAGE_TEXT = """
Usage: Link Play [options]

  (no options)                          open the app
  --data <dir>                          keep all files in <dir> (another copy of the app, tests)
  --selftest <report.txt>               check this build (headless); exit code 0 = all passed
  --check-rom <rom> [--seconds N] [--report <file>]
                                        run a real ROM headless for N seconds (default 5), play it silently,
                                        take snapshots, reload and report (the ROM is only read)
  --write-testrom <file>                write the built-in test ROM (a plain colour screen that reacts to A and B)
  --cli-host <rom> [--seconds N]        host a room without a window, print "INVITE <code>" (a test tool; uses a
                                        throw-away data folder unless --data is given)
  --cli-join <rom> <codeFile|-> [--seconds N]
                                        join a room from a file holding the invite ("-" = standard input);
                                        also a throw-away data folder unless --data is given
  --ui-snapshots <dir> [--data <dir>]   draw every screen into <dir> as PNG pictures, without a window (a design check)
  --version                             print the version
""".trimIndent()

internal enum class Mode { GUI, SELFTEST, CHECK_ROM, WRITE_TESTROM, CLI, UI_SNAPSHOTS, VERSION, HELP }

internal class UsageError(message: String) : Exception(message)

/** What the command line asked for. Parsing is pure, so it can be checked without starting anything. */
internal class Options(
    val mode: Mode,
    val dataDir: File?,
    val file: File?, // report (selftest), ROM (check-rom), output (write-testrom)
    val report: File?, // check-rom --report
    val seconds: Int?,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var mode = Mode.GUI
            var data: File? = null
            var file: File? = null
            var report: File? = null
            var seconds: Int? = null
            var i = 0
            fun value(flag: String): String {
                if (i + 1 >= args.size) throw UsageError("$flag needs a value")
                return args[++i]
            }
            fun setMode(m: Mode, flag: String) {
                if (mode != Mode.GUI) throw UsageError("$flag cannot be combined with another mode")
                mode = m
            }
            while (i < args.size) {
                when (val a = args[i]) {
                    "--data" -> data = File(value(a))
                    "--selftest" -> { setMode(Mode.SELFTEST, a); file = File(value(a)) }
                    "--check-rom" -> { setMode(Mode.CHECK_ROM, a); file = File(value(a)) }
                    "--write-testrom" -> { setMode(Mode.WRITE_TESTROM, a); file = File(value(a)) }
                    "--ui-snapshots" -> { setMode(Mode.UI_SNAPSHOTS, a); file = File(value(a)) }
                    "--cli-host", "--cli-join" -> setMode(Mode.CLI, a)
                    "--report" -> report = File(value(a))
                    "--seconds" -> seconds = value(a).toIntOrNull()?.takeIf { it in 1..3600 }
                        ?: throw UsageError("--seconds needs a number from 1 to 3600")
                    "--version" -> setMode(Mode.VERSION, a)
                    "--help", "-h", "/?" -> setMode(Mode.HELP, a)
                    else -> {
                        // Operating systems sometimes add arguments of their own (macOS: -psn_...).
                        // The command line modes are strict, the window is not.
                        if (mode != Mode.GUI && mode != Mode.CLI) {
                            throw UsageError("unknown argument: $a")
                        }
                    }
                }
                i++
            }
            return Options(mode, data, file, report, seconds)
        }
    }
}

fun main(args: Array<String>) {
    val opts = try {
        Options.parse(args)
    } catch (e: UsageError) {
        System.err.println("Link Play: ${e.message}")
        System.err.println(USAGE_TEXT)
        exitProcess(2)
    }
    val mac = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("mac")

    // These must be set before the first AWT class loads.
    System.setProperty("apple.awt.application.name", AppInfo.NAME)
    when (opts.mode) {
        Mode.GUI -> if (mac) System.setProperty("apple.laf.useScreenMenuBar", "true") // the menu goes in the Mac menu bar
        else -> System.setProperty("java.awt.headless", "true") // no window, no Dock icon, runs on a server too
    }

    when (opts.mode) {
        Mode.VERSION -> println("${AppInfo.NAME} ${AppInfo.VERSION}")
        Mode.HELP -> println(USAGE_TEXT)
        Mode.SELFTEST -> exitProcess(SelfTest.run(opts.file!!))
        Mode.CHECK_ROM -> {
            val report = opts.report ?: File.createTempFile("linkplay-checkrom", ".txt")
            exitProcess(SelfTest.checkRom(opts.file!!, opts.seconds ?: 5, report, opts.dataDir))
        }
        Mode.WRITE_TESTROM -> {
            println(TestRom.write(opts.file!!).absolutePath)
        }
        Mode.UI_SNAPSHOTS -> {
            // Never the real data folder: a throw-away one unless --data names another.
            val data = opts.dataDir ?: java.nio.file.Files.createTempDirectory("linkplay-ui-").toFile()
            startUp(Options(opts.mode, data, opts.file, opts.report, opts.seconds))
            exitProcess(com.linkplay.desktop.ui.Snapshots.run(opts.file!!))
        }
        Mode.CLI -> {
            // The test tool never touches the user's own data folder (it would import the ROM over the app's and
            // play on the real save): a throw-away one unless --data names another.
            val data = opts.dataDir ?: java.nio.file.Files.createTempDirectory("linkplay-cli-").toFile().also {
                System.err.println("using a throw-away data folder (pass --data <dir> to keep one)")
            }
            startUp(Options(opts.mode, data, opts.file, opts.report, opts.seconds))
            if (!AppDirs.lockInstance()) {
                System.err.println("Another Link Play is using ${AppDirs.root}. Use --data <dir> for a second copy.")
                exitProcess(3)
            }
            val code = try {
                Cli.main(args)
                0
            } catch (e: Throwable) {
                Log.e("cli", e)
                1
            }
            exitProcess(code) // the AWT event thread (used for callbacks) would keep the process alive
        }
        Mode.GUI -> {
            startUp(opts)
            if (!AppDirs.lockInstance()) {
                Log.w("another Link Play is already using ${AppDirs.root}")
                alreadyRunning()
                exitProcess(0)
            }
            Emulator.nativeProblem()?.let { Log.e("emulator library did not load: $it") }
            com.linkplay.desktop.ui.App.start(args)
        }
    }
}

/** Data folder, log, settings: the same for the window and the command line modes. */
private fun startUp(opts: Options) {
    AppDirs.init(opts.dataDir)
    Log.init()
    Settings.load()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.e("uncaught exception in thread ${t.name}", e) }
    Log.i("${AppInfo.NAME} ${AppInfo.VERSION} starting; Java ${System.getProperty("java.version")}, " +
        "${System.getProperty("os.name")} ${System.getProperty("os.arch")}; data ${AppDirs.root}")
    // One hook for every way the process can end without the window's own quit (SIGTERM, Ctrl+C, logout, the
    // CLI's exitProcess): say goodbye to the room and give the router its port back, then flush the log. It is
    // harmless after a normal quit (LinkSession.shutdown is a no-op the second time). No EDT here: it may be gone.
    Runtime.getRuntime().addShutdownHook(Thread({
        try {
            LinkSession.instance?.let {
                it.shutdown()
                it.awaitShutdown(2000)
            }
        } catch (_: Throwable) {}
        Log.close()
    }, "shutdown-hook"))
}

private fun alreadyRunning() {
    val text = "${AppInfo.NAME} is already running."
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println(text)
    } else {
        JOptionPane.showMessageDialog(null, text, AppInfo.NAME, JOptionPane.INFORMATION_MESSAGE)
    }
}
