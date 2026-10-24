package com.linkplay.desktop

import com.linkplay.app.Native
import com.linkplay.app.net.Codes
import com.linkplay.app.net.Invite
import com.linkplay.app.net.PacketCrypto
import com.linkplay.app.net.Reply
import com.linkplay.app.net.RomId
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `--selftest <report>`: a headless check that this build works, meant for the packaged app and CI:
 * the native library loads from the app folder, the core runs the built-in test ROM at about real
 * time with the right colours, keys and state save and load work, snapshots rotate, sound is pulled,
 * and invite codes and packet encryption work (that proves the cut-down Java runtime has the crypto).
 *
 * The report has one line per check starting with PASS or FAIL (INFO and WARN lines are notes; WARN is
 * a number that looks off but may only be a busy machine). The exit code is 0 only with no FAIL.
 * Everything happens in a fresh temporary data folder that is deleted at the end, never the user's.
 *
 * `--check-rom <rom>` ([checkRom]) is the same kind of check on a real ROM (read only).
 */
object SelfTest {
    private const val W = Native.WIDTH
    private const val H = Native.HEIGHT

    /** Runs everything; returns the process exit code. */
    fun run(reportFile: File): Int {
        val r = Report(reportFile)
        watchdog(r, 150)
        val tmp = Files.createTempDirectory("linkplay-selftest")
        var emu: Emulator? = null
        try {
            AppDirs.init(tmp.toFile())
            Log.init()
            Settings.load()
            r.info("Link Play ${AppInfo.VERSION}; Java ${System.getProperty("java.version")}; ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            emu = body(r, tmp)
        } catch (e: Throwable) {
            r.fail("self-test", e.toString())
        } finally {
            try { emu?.stop() } catch (_: Throwable) {}
            Log.close()
            AppDirs.unlockInstance()
            deleteTree(tmp)
        }
        return r.finish()
    }

    // ---- the checks --------------------------------------------------------------------------

    private fun body(r: Report, tmp: Path): Emulator? {
        r.step("data folder rules (macOS, Windows, Linux)") {
            val mac = AppDirs.defaultRoot("Mac OS X", { null }, "/Users/ann").toString().replace('\\', '/')
            need(mac == "/Users/ann/Library/Application Support/Link Play") { "macOS: $mac" }
            val win = AppDirs.defaultRoot("Windows 11", { if (it == "LOCALAPPDATA") "C:\\Users\\ann\\AppData\\Local" else null }, "C:\\Users\\ann")
                .toString().replace('\\', '/')
            need(win == "C:/Users/ann/AppData/Local/Link Play") { "Windows: $win" }
            val winNoEnv = AppDirs.defaultRoot("Windows 10", { null }, "C:\\Users\\ann").toString().replace('\\', '/')
            need(winNoEnv == "C:/Users/ann/AppData/Local/Link Play") { "Windows without LOCALAPPDATA: $winNoEnv" }
            val lin = AppDirs.defaultRoot("Linux", { null }, "/home/ann").toString().replace('\\', '/')
            need(lin == "/home/ann/.local/share/link-play") { "Linux: $lin" }
            "ok"
        }

        r.step("settings: defaults, save, reload, keys") {
            Settings.load()
            need(Settings.filter == Settings.FILTER_SHARP && Settings.scale == 3 && !Settings.stun) { "defaults" }
            need(Math.abs(Settings.volume - 0.8f) < 1e-6f) { "default volume ${Settings.volume}" }
            need(Settings.buttonFor(0x58) == Button.A && Settings.key(Button.START) == 0x0A) { "default keys" }
            Settings.name = "  Ana "
            Settings.filter = Settings.FILTER_SMOOTH
            Settings.volume = 0.25f
            Settings.setKey(Button.A, 0x43)
            Settings.load() // from the file
            need(Settings.name == "Ana" && Settings.playerName == "Ana") { "name" }
            need(Settings.filter == Settings.FILTER_SMOOTH && Math.abs(Settings.volume - 0.25f) < 1e-6f) { "reload" }
            need(Settings.buttonFor(0x43) == Button.A && Settings.buttonFor(0x58) == null) { "rebound key" }
            Settings.resetKeys()
            Settings.name = ""
            need(Settings.playerName == "Player" && Settings.key(Button.A) == 0x58) { "reset" }
            need(!File(AppDirs.settingsFile.path + ".tmp").exists()) { "temporary file left behind" }
            "ok"
        }

        r.step("network rules: transition IPv6, VPN names") {
            fun v6(s: String) = java.net.InetAddress.getByName(s)
            need(DesktopNet.isTransitionV6(v6("2001:0:4137:9e76::1"))) { "Teredo not recognised" }
            need(DesktopNet.isTransitionV6(v6("2002:c000:204::1"))) { "6to4 not recognised" }
            need(!DesktopNet.isTransitionV6(v6("2001:4860:4860::8888"))) { "Google's IPv6 taken for a tunnel" }
            need(!DesktopNet.isTransitionV6(v6("2a00:1450::1"))) { "a global address taken for a tunnel" }
            need(!DesktopNet.isTransitionV6(v6("2001:db8::1")) && !DesktopNet.isTransitionV6(v6("2001:1::1"))) { "2001:x taken for Teredo" }
            need(!DesktopNet.isTransitionV6(v6("192.168.1.5"))) { "an IPv4 address taken for a tunnel" }
            for (n in listOf("NordLynx Tunnel", "ProtonVPN TUN", "Mullvad Network Adapter", "Surfshark WireGuard", "PANGP Virtual Ethernet Adapter",
                "Fortinet Virtual Ethernet Adapter", "FortiClient SSLVPN", "SonicWall Virtual Adapter", "Juniper Network Connect", "Pulse Secure Virtual Adapter",
                "WireGuard Tunnel", "Tunnel adapter OpenVPN", "Cisco AnyConnect Secure Mobility Client", "TAP-Windows Adapter V9")) {
                need(DesktopNet.displayLooksLikeVpn(n)) { "VPN name not recognised: $n" }
            }
            for (n in listOf("Intel(R) Ethernet Connection I219-V", "Realtek PCIe GbE Family Controller", "Intel(R) Wi-Fi 6 AX201 160MHz",
                "Teredo Tunneling Pseudo-Interface", "Microsoft ISATAP Adapter", "Microsoft 6to4 Adapter", "Broadcom 802.11ac Network Adapter", "")) {
                need(!DesktopNet.displayLooksLikeVpn(n)) { "ordinary adapter taken for a VPN: $n" }
            }
            r.info("network now: " + DesktopNet.snapshot().describe())
            "ok"
        }

        val romBytes = TestRom.bytes()
        val romFile = File(tmp.toFile(), "test.gba")
        TestRom.write(romFile)
        r.step("test ROM looks like a GBA ROM") {
            need(romBytes.size == TestRom.SIZE && (romBytes[0xB2].toInt() and 0xFF) == 0x96) { "header" }
            need((romBytes[3].toInt() and 0xFF) == 0xEA) { "first instruction is not a branch" }
            var sum = 0x19
            for (i in 0xA0..0xBD) sum += romBytes[i].toInt() and 0xFF
            need(sum and 0xFF == 0) { "header check byte" }
            "${romBytes.size} bytes"
        }

        r.step("ROM import: checks, copy, keep only the newest") {
            val tooSmall = File(tmp.toFile(), "small.bin").apply { writeBytes(ByteArray(10)) }
            val noHeader = File(tmp.toFile(), "zeros.bin").apply { writeBytes(ByteArray(0x400)) }
            val e1 = failure { RomStore.import(tooSmall) }
            need(e1 is IllegalArgumentException && e1.message!!.contains("wrong size")) { "small file: $e1" }
            val e2 = failure { RomStore.import(noHeader) }
            need(e2 is IllegalArgumentException && e2.message!!.contains("bad header")) { "no header: $e2" }
            val e3 = failure { RomStore.import(File(tmp.toFile(), "missing.gba")) }
            need(e3 is IllegalArgumentException && e3.message!!.contains("Could not open")) { "missing file: $e3" }
            need(RomStore.current() == null && !File(AppDirs.romsDir, "import.tmp").exists()) { "rejected files left something" }
            // RomStore.check: the same verdicts without a copy.
            val zip = File(tmp.toFile(), "rom.zip").apply { writeBytes(ByteArray(0x400).also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }) }
            need(RomStore.check(zip)?.startsWith("Zip files aren't supported") == true) { "check(zip): ${RomStore.check(zip)}" }
            need(RomStore.check(tooSmall)?.contains("wrong size") == true) { "check(10 bytes): ${RomStore.check(tooSmall)}" }
            need(RomStore.check(noHeader)?.contains("bad header") == true) { "check(zeros): ${RomStore.check(noHeader)}" }
            need(RomStore.check(File(tmp.toFile(), "missing.gba")) == "Could not open the file.") { "check(missing): ${RomStore.check(File(tmp.toFile(), "missing.gba"))}" }
            need(RomStore.check(tmp.toFile()) == "Could not open the file.") { "check(folder): ${RomStore.check(tmp.toFile())}" }
            need(RomStore.check(romFile) == null) { "check(good ROM): ${RomStore.check(romFile)}" }
            need(RomStore.current() == null && AppDirs.romsDir.list().isNullOrEmpty()) { "check() copied something: ${AppDirs.romsDir.list()?.toList()}" }
            // A first ROM with snapshots, then a different one: the first ROM goes, its snapshots stay
            // (sha1-keyed, so they never match the new ROM and are usable again if the old one returns).
            val first = romBytes.copyOf().also { it[0x1F0] = 1 }
            val firstFile = File(tmp.toFile(), "first.gba").apply { writeBytes(first) }
            val a = RomStore.import(firstFile)
            val oldStates = RomStore.statesDir(a.sha1).apply { mkdirs() }
            File(oldStates, "auto.ss").writeText("x")
            File(oldStates, "slot1.ss").writeText("y")
            val b = RomStore.import(romFile)
            need(a.sha1 != b.sha1) { "two different ROMs have one hash" }
            need(!File(a.path).exists()) { "old ROM file kept" }
            need(File(oldStates, "auto.ss").isFile && File(oldStates, "slot1.ss").isFile) { "old ROM's snapshots were deleted on a ROM change: ${oldStates.list()?.toList()}" }
            RomStore.clearSnapshots(a.sha1)
            need(!oldStates.exists()) { "clearSnapshots left the folder" }
            val md = MessageDigest.getInstance("SHA-1").digest(romBytes).joinToString("") { "%02x".format(it) }
            need(b.sha1 == md && b.size == romBytes.size.toLong()) { "hash or size" }
            need(b.title == TestRom.TITLE && b.code == TestRom.CODE) { "title ${b.title} code ${b.code}" }
            val cur = RomStore.current()
            need(cur != null && cur.sha1 == b.sha1 && File(cur.path).isFile) { "current()" }
            need(File(AppDirs.romsDir, "${b.sha1}.gba").isFile && AppDirs.romsDir.listFiles()!!.size == 1) { "roms folder: ${AppDirs.romsDir.list()?.toList()}" }
            "${b.shortHash}, ${b.title}"
        }
        val rom = RomStore.current() ?: return null
        val save = RomStore.saveFile(rom)

        r.step("native library loads and links") {
            val p = Emulator.nativeProblem()
            need(p == null) { p ?: "" }
            "libgbalink"
        }

        r.step("a failed load leaves nothing behind") {
            val bad = Emulator(withAudio = false)
            need(!bad.load(File(tmp.toFile(), "missing.gba"), save)) { "loading a missing ROM worked" }
            need(!bad.load(noRom(tmp), save)) { "loading a non-ROM worked" }
            need(!Emulator.coreLoaded && !bad.isLoaded) { "still loaded" }
            bad.stop()
            "ok"
        }

        val emu = Emulator(withAudio = true)
        var frames = 0L
        emu.frameSink = { frames++ }
        r.step("core loads the test ROM") {
            need(emu.load(File(rom.path), save)) { "Native.load failed" }
            need(Emulator.coreLoaded && emu.isLoaded) { "state flags" }
            val e = failure { RomStore.import(romFile) }
            need(e is IllegalStateException) { "ROM replaced under a running core: $e" }
            val e2 = failure { RomStore.importSave(rom, romFile) }
            need(e2 is IllegalStateException) { "save replaced under a running core: $e2" }
            emu.setVolume(0.8f)
            emu.start()
            "ok"
        }

        r.step("core runs") {
            val f0 = emu.frameCount()
            val t0 = System.nanoTime()
            val got = waitFor(6000) { emu.frameCount() - f0 >= 60 }
            val dt = (System.nanoTime() - t0) / 1e9
            val df = emu.frameCount() - f0
            need(df >= 20) { "only $df frames in %.1f s".format(dt) }
            val fps = df / dt
            r.info("speed: %d frames in %.2f s = %.1f fps (a real GBA: 59.7)".format(df, dt, fps))
            if (fps < 45 || fps > 75) r.warn("speed %.1f fps is not close to 59.7: busy or throttled machine?".format(fps))
            if (!got) r.warn("did not reach 60 frames in 6 s")
            "%d frames".format(df)
        }

        r.step("picture: the test colour, right way round") {
            need(waitFor(4000) { centre(emu) == TestRom.IDLE_RGB }) { "centre pixel is 0x%08X, wanted 0x%08X".format(centre(emu), TestRom.IDLE_RGB) }
            val px = emu.grabFrame() ?: throw AssertionError("no frame")
            for ((x, y) in listOf(0 to 0, W - 1 to 0, 0 to H - 1, W - 1 to H - 1, 17 to 123)) {
                need(px[y * W + x] == TestRom.IDLE_RGB) { "pixel ($x,$y) is 0x%08X".format(px[y * W + x]) }
            }
            need(frames > 0) { "the frame sink never ran" }
            "0x%08X everywhere".format(TestRom.IDLE_RGB)
        }

        r.step("buttons reach the game (A green, B blue, pulse)") {
            emu.setButtons(Native.A)
            need(waitFor(5000) { centre(emu) == TestRom.GREEN_RGB }) { "A: centre 0x%08X".format(centre(emu)) }
            emu.setButtons(0)
            waitFrames(emu, 8, 4000)
            need(centre(emu) == TestRom.GREEN_RGB) { "colour not kept after release: 0x%08X".format(centre(emu)) }
            emu.setButtons(Native.B)
            need(waitFor(5000) { centre(emu) == TestRom.BLUE_RGB }) { "B: centre 0x%08X".format(centre(emu)) }
            emu.releaseButtons()
            emu.pulse(Native.A, 200)
            need(waitFor(5000) { centre(emu) == TestRom.GREEN_RGB }) { "pulse: centre 0x%08X".format(centre(emu)) }
            Thread.sleep(400) // let the 200 ms pulse end: a button still held would change the colour in the next steps
            "ok"
        }

        val slot = File(AppDirs.statesDir(rom.sha1), "slot1.ss")
        r.step("save state, then load it back") {
            waitFrames(emu, 5, 4000)
            val done = CountDownLatch(1)
            val ok = AtomicBoolean()
            emu.saveState(slot) { ok.set(it); done.countDown() } // green is on screen and in register r3
            need(done.await(8, TimeUnit.SECONDS) && ok.get()) { "saveState failed" }
            need(slot.isFile && slot.length() > 1000) { "state file is ${slot.length()} bytes" }
            need(!File(slot.path + ".tmp").exists()) { "temporary file left behind" }
            emu.setButtons(Native.B)
            need(waitFor(5000) { centre(emu) == TestRom.BLUE_RGB }) { "never went blue" }
            emu.releaseButtons()
            waitFrames(emu, 6, 4000)
            need(centre(emu) == TestRom.BLUE_RGB) { "blue not kept" }
            val loaded = CountDownLatch(1)
            val ok2 = AtomicBoolean()
            emu.loadState(slot) { ok2.set(it); loaded.countDown() }
            need(loaded.await(8, TimeUnit.SECONDS) && ok2.get()) { "loadState failed" }
            need(waitFor(5000) { centre(emu) == TestRom.GREEN_RGB }) { "state not restored: centre 0x%08X".format(centre(emu)) }
            waitFrames(emu, 8, 4000)
            need(centre(emu) == TestRom.GREEN_RGB) { "state did not stay: centre 0x%08X".format(centre(emu)) }
            need(!emu.loadStateBlocking(File(AppDirs.root, "nothing.ss"))) { "loaded a file that is not there" }
            need(!emu.loadStateBlocking(File(tmp.toFile(), "test.gba"))) { "loaded a ROM as a state" }
            "${slot.length()} bytes"
        }

        r.step("autosave: guard, rotation, stamps, quit snapshot") {
            val auto = AutoSave(emu) { rom.sha1 }
            need(!awaitSnapshot(auto, AutoSave.Kind.AUTO)) { "a snapshot of a fresh game was taken" }
            need(awaitSnapshot(auto, AutoSave.Kind.BEFORE_LINK)) { "before-link snapshot failed" }
            val bl = auto.beforeLinkFile() ?: throw AssertionError("no before-link.ss")
            need(File(bl.path + AutoSave.SAVE_STAMP).isFile) { "no stamp next to before-link.ss" }
            need(auto.newestUsable()?.name == "before-link.ss") { "newestUsable: ${auto.newestUsable()?.name}" }
            need(auto.tick(AutoSave.EVERY_MS, running = true, linked = false)) { "tick says not due after 5 minutes" }
            need(!auto.tick(AutoSave.EVERY_MS, running = true, linked = true)) { "tick says due while linked" }
            need(!auto.tick(AutoSave.EVERY_MS, running = false, linked = false)) { "tick counts while not running" }
            need(auto.snapshotNow()) { "quit snapshot not taken" }
            val a1 = auto.autoFile() ?: throw AssertionError("no auto.ss")
            need(a1.name == "auto.ss" && File(a1.path + AutoSave.SAVE_STAMP).isFile) { "auto.ss or its stamp" }
            need(!awaitSnapshot(auto, AutoSave.Kind.AUTO)) { "two snapshots in a row" } // sinceSave is 0 again
            auto.tick(AutoSave.EVERY_MS, running = true, linked = false)
            need(awaitSnapshot(auto, AutoSave.Kind.AUTO)) { "second periodic snapshot failed" }
            val dir = AppDirs.statesDir(rom.sha1)
            for (n in listOf("auto.ss", "auto-prev.ss", "auto.ss.save", "auto-prev.ss.save", "before-link.ss", "before-link.ss.save")) {
                need(File(dir, n).isFile) { "missing $n (have ${dir.list()?.sorted()})" }
            }
            need(!File(dir, "auto-new.ss").exists() && !File(dir, "auto-new.ss.save").exists()) { "auto-new left behind" }
            need(auto.newestUsable() != null && auto.newest() != null) { "nothing to continue from" }
            need(auto.agoText(a1) == "less than a minute ago") { auto.agoText(a1) }

            // The stamp logic on a save file we control (the core's own is not touched): changed data = stale,
            // a changed 16-byte clock footer alone = still good.
            val fake = "0123456789abcdef0123456789abcdef01234567"
            val other = AutoSave(emu) { fake }
            val fakeSave = File(AppDirs.savesDir, "$fake.sav")
            fakeSave.writeBytes(ByteArray(1024 + 16) { it.toByte() })
            need(awaitSnapshot(other, AutoSave.Kind.BEFORE_LINK)) { "snapshot for the second key failed" }
            need(other.newestUsable() != null) { "fresh snapshot not usable" }
            val footer = ByteArray(1024 + 16) { it.toByte() }.also { it[1030] = 99 }
            fakeSave.writeBytes(footer)
            need(other.newestUsable() != null) { "clock footer change made the snapshot stale" }
            fakeSave.writeBytes(ByteArray(1024 + 16) { it.toByte() }.also { it[5] = 99 })
            need(other.newestUsable() == null && other.newest() != null) { "changed save still counted as usable" }
            fakeSave.writeBytes(ByteArray(0))
            need(other.newestUsable() == null) { "emptied save counted as usable" }
            RomStore.clearSnapshots(fake)
            fakeSave.delete()
            "rotation, stamps, guard"
        }

        r.step("sound is pulled from the core") {
            val a = emu.audio ?: throw AssertionError("no audio object")
            waitFrames(emu, 30, 6000)
            need(a.framesRequested > 0) { "audio thread never pulled" }
            need(a.framesReal > 0) { "the core made no sound frames (0 of ${a.framesRequested})" }
            val pct = 100.0 * a.framesReal / a.framesRequested
            r.info("audio: %d of %d frames real (%.0f%%), %d short pulls; device: %s".format(
                a.framesReal, a.framesRequested, pct, a.shortPulls, if (a.hasDevice) "open" else "none (${a.problem})"))
            if (pct < 80) r.warn("audio: only %.0f%% of what the card asked for was ready (busy or throttled machine?)".format(pct))
            "%d frames".format(a.framesReal)
        }

        r.step("fast forward and link rules") {
            emu.setFastForward(true)
            val f0 = emu.frameCount()
            val t0 = System.nanoTime()
            Thread.sleep(500)
            val ff = (emu.frameCount() - f0) / ((System.nanoTime() - t0) / 1e9)
            need(emu.fastForward) { "fast forward did not turn on" }
            emu.setLinked(true)
            need(!emu.fastForward) { "fast forward still on while linked" }
            emu.setFastForward(true)
            need(!emu.fastForward) { "fast forward turned on while linked" }
            emu.setLinked(false)
            emu.setFastForward(false)
            r.info("speed at 4x: %.0f fps".format(ff))
            "ok"
        }

        r.step("stop, then load the game again in the same process") {
            emu.stop()
            need(!emu.isLoaded && !Emulator.coreLoaded) { "still loaded after stop" }
            emu.stop() // twice is fine
            need(emu.load(File(rom.path), save)) { "second load failed" }
            emu.start()
            val f0 = emu.frameCount()
            need(waitFor(6000) { emu.frameCount() - f0 >= 20 }) { "no frames after the second load" }
            need(emu.loadStateBlocking(slot)) { "loadState after reload failed" }
            need(waitFor(5000) { centre(emu) == TestRom.GREEN_RGB }) { "state after reload: centre 0x%08X".format(centre(emu)) }
            "ok"
        }

        r.step("stop; save import and export") {
            emu.stop()
            need(!RomStore.hasSave(rom)) { "the test game has a save?" }
            val e = failure { RomStore.exportSave(rom, File(tmp.toFile(), "out.sav")) }
            need(e is IllegalArgumentException && e.message!!.startsWith("No in-game save")) { "export without save: $e" }
            val mine = ByteArray(2048) { (it * 7).toByte() }
            save.writeBytes(mine)
            val out = File(tmp.toFile(), "out.sav")
            out.writeText("old file")
            RomStore.exportSave(rom, out)
            need(out.readBytes().contentEquals(mine)) { "exported bytes differ" }
            need(RomStore.exportName(rom).matches(Regex("LINKPLAYTEST \\d{4}-\\d{2}-\\d{2} \\d{4}\\.sav"))) { RomStore.exportName(rom) }
            // Snapshots of this game: a failed import must leave all of them, a good one drops only the automatic ones.
            val states = AppDirs.statesDir(rom.sha1).apply { mkdirs() }
            for (n in listOf("slot1.ss", "slot1.png", "slot1.ss.save", "auto.ss", "auto.ss.save", "auto-prev.ss", "before-link.ss", "before-link.ss.save", "last-frame.png")) {
                File(states, n).writeText("x")
            }
            fun stillThere(vararg names: String) = names.all { File(states, it).isFile }
            fun backups() = save.parentFile.listFiles()!!.filter { Regex(Regex.escape(save.name) + "\\.\\d{8}-\\d{6}\\.bak").matches(it.name) }
            // Refused imports: nothing changes (save, snapshots, backups).
            val missing = failure { RomStore.importSave(rom, File(tmp.toFile(), "no-such.sav")) }
            need(missing is IllegalArgumentException && missing.message!!.contains("Could not open")) { "missing save file: $missing" }
            val empty = failure { RomStore.importSave(rom, File(tmp.toFile(), "empty.sav").apply { writeBytes(ByteArray(0)) }) }
            need(empty is IllegalArgumentException && empty.message == "That doesn't look like a GBA save (wrong size).") { "0-byte save: $empty" }
            val huge = failure { RomStore.importSave(rom, File(tmp.toFile(), "huge.sav").apply { writeBytes(ByteArray(262144 + 1)) }) }
            need(huge is IllegalArgumentException && huge.message!!.contains("wrong size")) { "257 KiB save: $huge" }
            need(save.readBytes().contentEquals(mine) && backups().isEmpty() && !File(save.path + ".tmp").exists()) { "a refused import touched the save or made a backup" }
            need(stillThere("slot1.ss", "slot1.png", "slot1.ss.save", "auto.ss", "auto-prev.ss", "before-link.ss", "last-frame.png")) { "a refused import cost snapshots: ${states.list()?.sorted()}" }
            // Exactly 256 KiB is still fine (variants exist).
            val limit = File(tmp.toFile(), "limit.sav").apply { writeBytes(ByteArray(262144) { 1 }) }
            RomStore.importSave(rom, limit)
            need(save.length() == 262144L) { "256 KiB save not accepted" }
            save.writeBytes(mine)
            val picked = File(tmp.toFile(), "picked.sav").apply { writeBytes(ByteArray(300) { 5 }) }
            RomStore.importSave(rom, picked)
            need(save.readBytes().contentEquals(picked.readBytes())) { "imported save differs" }
            need(backups().any { it.readBytes().contentEquals(mine) }) { "the replaced save was not kept as .sav.<stamp>.bak (${save.parentFile.list()?.sorted()})" }
            need(!File(save.path + ".bak").exists()) { "the old fixed .bak name is back" }
            need(stillThere("slot1.ss", "slot1.png", "slot1.ss.save")) { "a save import deleted the manual slot: ${states.list()?.sorted()}" }
            need(!stillThere("auto.ss") && !stillThere("auto.ss.save") && !stillThere("auto-prev.ss") && !stillThere("before-link.ss") &&
                !stillThere("before-link.ss.save") && !stillThere("last-frame.png")) { "automatic snapshots kept after a save import: ${states.list()?.sorted()}" }
            need(!File(save.path + ".tmp").exists()) { "temporary save left behind" }
            // Backups are never overwritten and only the newest 5 stay.
            for (i in 1..8) {
                RomStore.importSave(rom, File(tmp.toFile(), "again$i.sav").apply { writeBytes(ByteArray(64) { i.toByte() }) })
            }
            val kept = backups()
            need(kept.size == 5) { "wanted 5 backups, have ${kept.map { it.name }.sorted()}" }
            need(kept.any { it.readBytes().contentEquals(ByteArray(64) { 7.toByte() }) }) { "the newest backup is missing" }
            need(kept.none { it.readBytes().contentEquals(mine) }) { "the oldest backup was not pruned" }
            "ok, ${kept.size} backups kept"
        }

        r.step("invite codes, reply codes and packet encryption") {
            val id = RomId(rom.sha1, rom.title, rom.code, rom.size)
            val invite = Codes.newInvite("Ann", "Ann's room", id, 3, emptyList())
            val text = invite.encode()
            need(text.startsWith("LP1I-")) { "invite prefix" }
            val seen = Codes.find("Join me!\n$text\nThanks")
            need(seen is Invite && seen.roomId == invite.roomId && seen.secret.contentEquals(invite.secret)) { "invite round trip: $seen" }
            val found = seen as Invite
            need(found.hostName == "Ann" && found.maxPlayers == 3 && found.romMatches(rom.sha1)) { "invite fields" }
            val reply = Codes.reply(found.key, Codes.newConnId(), "Bo", emptyList())
            val back = Codes.find(reply, invite.key)
            need(back is Reply && back.guestName == "Bo" && back.verify(invite.key)) { "reply round trip: $back" }
            val wrongRoom = Codes.newInvite("Cy", "x", id, 2, emptyList())
            need(Codes.find(reply, wrongRoom.key) !is Reply) { "a reply for another room was accepted" }
            val crypto = PacketCrypto(invite.key)
            val sealed = crypto.seal("hello link".toByteArray(), 10)
            need(String(crypto.open(sealed, sealed.size)!!) == "hello link") { "AES-GCM round trip" }
            sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
            need(crypto.open(sealed, sealed.size) == null) { "a damaged packet was accepted" }
            "SHA-1, HMAC-SHA256, AES-GCM"
        }

        r.step("log file") {
            Log.i("self-test log line")
            need(AppDirs.logFile.isFile && AppDirs.logFile.readText().contains("self-test log line")) { "log.txt" }
            "ok"
        }
        return emu
    }

    // ---- check on a real ROM -----------------------------------------------------------------

    /**
     * `--check-rom <rom> [--seconds N]`: imports [rom] (a read-only copy goes into a temporary data
     * folder, or [dataDir]), runs it for [seconds], takes snapshots, stops, loads again in the same
     * process and loads a state; reports speed, sound and which snapshot would be continued from.
     * Sound is played at volume 0.
     */
    fun checkRom(rom: File, seconds: Int, reportFile: File, dataDir: File?): Int {
        val r = Report(reportFile)
        watchdog(r, seconds + 120L)
        val tmp = dataDir?.toPath() ?: Files.createTempDirectory("linkplay-checkrom")
        val emu = Emulator(withAudio = true)
        try {
            AppDirs.init(tmp.toFile())
            Log.init()
            r.info("Link Play ${AppInfo.VERSION}; Java ${System.getProperty("java.version")}; data folder ${AppDirs.root}")
            var info: RomInfo? = null
            r.step("import ${rom.name}") {
                val i = RomStore.import(rom)
                info = i
                "${i.title} (${i.code}), ${i.size} bytes, sha1 ${i.shortHash}"
            }
            val ri = info ?: return r.finish()
            val save = RomStore.saveFile(ri)
            val auto = AutoSave(emu) { ri.sha1 }
            val slot = File(AppDirs.statesDir(ri.sha1), "slot1.ss")
            r.step("load and run for $seconds s") {
                need(emu.load(File(ri.path), save)) { "Native.load failed" }
                emu.setVolume(0f) // played silently; the sound is still pulled and counted
                emu.start()
                var published = 0L
                emu.frameSink = { published++ }
                val f0 = emu.frameCount()
                val t0 = System.nanoTime()
                Thread.sleep(seconds * 1000L)
                val dt = (System.nanoTime() - t0) / 1e9
                val df = emu.frameCount() - f0
                val a = emu.audio!!
                val px = emu.grabFrame() ?: throw AssertionError("no picture")
                val colours = px.toSet().size
                need(df > 0) { "no frames" }
                need(colours > 1) { "the picture is one flat colour" }
                need(a.framesReal > 0) { "no sound frames" }
                r.info("speed: %d frames in %.2f s = %.1f fps; %d pictures reached the frame sink".format(df, dt, df / dt, published))
                r.info("audio: %d of %d frames real (%.0f%%), %d short pulls; device: %s".format(
                    a.framesReal, a.framesRequested, 100.0 * a.framesReal / a.framesRequested, a.shortPulls,
                    if (a.hasDevice) "open" else "none (${a.problem})"))
                "%.1f fps, %d colours on screen".format(df / dt, colours)
            }
            r.step("save state slot 1, before-link and quit snapshots") {
                need(emu.saveStateBlocking(slot)) { "saveState failed" }
                auto.stampSave(slot)
                need(slot.length() > 100_000) { "state file is only ${slot.length()} bytes" }
                need(awaitSnapshot(auto, AutoSave.Kind.BEFORE_LINK)) { "before-link snapshot failed" }
                Thread.sleep(1100) // file times can be whole seconds on some systems
                auto.tick(AutoSave.EVERY_MS, running = true, linked = false)
                need(auto.snapshotNow()) { "quit snapshot not taken" }
                val newest = auto.newestUsable()
                need(newest?.name == "auto.ss") { "newestUsable is ${newest?.name}, wanted auto.ss (the newer one)" }
                need(auto.matchesSave(slot)) { "slot stamp does not match the save" }
                "slot ${slot.length()} bytes; newestUsable = ${newest?.name}"
            }
            r.step("stop, load again in the same process, load the state") {
                emu.stop()
                need(!emu.isLoaded) { "still loaded" }
                need(emu.load(File(ri.path), save)) { "second load failed" }
                emu.setVolume(0f)
                emu.start()
                val f0 = emu.frameCount()
                need(waitFor(10_000) { emu.frameCount() - f0 >= 30 }) { "no frames after reload" }
                need(emu.loadStateBlocking(slot)) { "loadState failed" }
                val f1 = emu.frameCount()
                need(waitFor(10_000) { emu.frameCount() - f1 >= 30 }) { "no frames after loadState" }
                need(auto.newestUsable()?.name == "auto.ss") { "newestUsable changed: ${auto.newestUsable()?.name}" }
                "ok"
            }
            r.step("pause, resume, fast forward") {
                emu.setPaused(true)
                Thread.sleep(300)
                val p0 = emu.frameCount()
                Thread.sleep(500)
                need(emu.frameCount() == p0) { "frames advanced while paused" }
                emu.setPaused(false)
                emu.setFastForward(true)
                val f0 = emu.frameCount()
                Thread.sleep(500)
                val df = emu.frameCount() - f0
                emu.setFastForward(false)
                need(df > 0) { "no frames after resume" }
                "paused held still; %d frames in 0.5 s at 4x".format(df)
            }
            r.step("stop; a changed in-game save makes the snapshots stale") {
                emu.stop()
                val before = save.length()
                save.appendBytes(byteArrayOf(1))
                need(auto.newestUsable() == null) { "a snapshot older than the save is still offered (save was $before bytes)" }
                need(auto.newest() != null) { "newest() found nothing" }
                "ok"
            }
        } catch (e: Throwable) {
            r.fail("check-rom", e.toString())
        } finally {
            try { emu.stop() } catch (_: Throwable) {}
            Log.close()
            AppDirs.unlockInstance()
            if (dataDir == null) deleteTree(tmp)
        }
        return r.finish()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun centre(emu: Emulator): Int {
        val px = emu.grabFrame() ?: return 0
        return px[(H / 2) * W + W / 2]
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun waitFrames(emu: Emulator, n: Long, timeoutMs: Long) {
        val f0 = emu.frameCount()
        waitFor(timeoutMs) { emu.frameCount() - f0 >= n }
    }

    private fun awaitSnapshot(auto: AutoSave, kind: AutoSave.Kind): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean()
        auto.snapshot(kind) { ok.set(it); latch.countDown() }
        latch.await(8, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun noRom(tmp: Path): File = File(tmp.toFile(), "zeros.bin")

    private fun failure(block: () -> Unit): Throwable? = try { block(); null } catch (e: Throwable) { e }

    private fun need(cond: Boolean, msg: () -> String) {
        if (!cond) throw AssertionError(msg())
    }

    private fun deleteTree(p: Path) {
        try { p.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    /** If a check hangs, say so in the report and stop the process: CI must never wait forever. */
    private fun watchdog(r: Report, seconds: Long) {
        val t = Thread({
            try { Thread.sleep(seconds * 1000) } catch (_: InterruptedException) { return@Thread }
            r.fail("watchdog", "the self-test did not finish within $seconds s")
            Runtime.getRuntime().halt(1)
        }, "selftest-watchdog")
        t.isDaemon = true
        t.start()
    }

    /** The report file: one flushed line at a time, so a crash still leaves what was done. */
    private class Report(file: File) {
        private val out = PrintWriter(Files.newBufferedWriter(file.absoluteFile.also { it.parentFile?.mkdirs() }.toPath(), Charsets.UTF_8), true)
        private var fails = 0
        private var passes = 0

        @Synchronized private fun line(s: String) {
            out.println(s)
            System.out.println(s)
        }

        fun pass(name: String, detail: String) { passes++; line("PASS $name: $detail") }
        @Synchronized fun fail(name: String, detail: String) { fails++; line("FAIL $name: $detail") }
        fun info(s: String) = line("INFO $s")
        fun warn(s: String) = line("WARN $s")

        inline fun step(name: String, body: () -> String) {
            try {
                pass(name, body())
            } catch (e: Throwable) {
                fail(name, e.message ?: e.toString())
            }
        }

        @Synchronized fun finish(): Int {
            line(if (fails == 0) "DONE $passes checks passed" else "DONE $fails FAILED, $passes passed")
            out.close()
            return if (fails == 0 && passes > 0) 0 else 1
        }
    }
}
