package com.linkplay.desktop

import com.linkplay.app.Native
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * The running game: owns the core's life (load, start, pause, stop) and the two threads that carry
 * its output to the screen and the speakers. One per process: the native core is a singleton, so a
 * second Emulator cannot load while the first has a game (load returns false).
 *
 * Threads:
 *  - the core's own emulation thread (native), steady 59.7 Hz clock;
 *  - "frame-pump" (here): polls the frame counter about every 4 ms and hands each new picture to
 *    [frameSink]; never blocks on the UI;
 *  - "audio-out" ([AudioOut]): pulls sound and writes it to the sound card;
 *  - "autosave" (here): the one thread for the slow native calls (snapshots, state slots), shared
 *    with [AutoSave].
 *
 * Rules: [load], [stop] and the blocking state calls take a moment, so not on the UI thread
 * ([stop] is quick enough for a quit path). Only the frame sink and state callbacks run on our
 * threads; hop to the UI thread with invokeLater and never block in them.
 */
class Emulator(withAudio: Boolean = true) {
    /** The sound side (null for a headless run); its counters are handy for checks. */
    val audio: AudioOut? = if (withAudio) AudioOut() else null

    /**
     * Gets every new picture: 240x160 ints 0xFFRRGGBB, row by row, on the frame-pump thread. Copy
     * what you need before returning (or hand the array on: it is only written again after two more
     * pictures have been delivered, about 30 ms later). Must not block.
     */
    @Volatile var frameSink: ((IntArray) -> Unit)? = null

    @Volatile var isLoaded = false
        private set
    @Volatile var isRunning = false
        private set
    @Volatile var isPaused = false
        private set

    /** Fast forward (4x) is on. Never while [linked]. */
    @Volatile var fastForward = false
        private set

    /** A link room is in use: fast forward is off and stays off. */
    @Volatile var linked = false
        private set

    /** Master volume 0..1 (see also [muted]). */
    @Volatile var volume = Settings.DEFAULT_VOLUME
        private set
    @Volatile var muted = false
        private set

    private var pump: Pump? = null
    private val stateExec: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "autosave").apply { isDaemon = true } }

    // ---- life cycle --------------------------------------------------------------------------

    /**
     * Opens the ROM and its cartridge save (created if missing). Blocking. False if the library is
     * missing, the files cannot be opened, or another Emulator has a game; nothing is left half set up.
     */
    @Synchronized
    fun load(rom: String, save: String): Boolean {
        if (isLoaded) return true
        if (coreLoaded) {
            Log.w("load: another Emulator already has a game")
            return false
        }
        if (!pathFits(rom) || !pathFits(save)) {
            Log.w("load: path too long for the emulator core")
            return false
        }
        try { File(save).absoluteFile.parentFile?.mkdirs() } catch (_: Exception) {}
        val ok = try {
            Native.load(rom, save)
        } catch (e: Throwable) { // UnsatisfiedLinkError, or ExceptionInInitializerError from Native's loadLibrary
            Log.e("load: emulator library", e)
            false
        }
        if (!ok) {
            Log.w("load: the core could not open the game")
            return false
        }
        isLoaded = true
        coreLoaded = true
        Native.setSpeed(1)
        fastForward = false
        applyVolume()
        return true
    }

    fun load(rom: File, save: File): Boolean = load(rom.absolutePath, save.absolutePath)

    /** Starts running (and the picture and sound threads). The game begins unpaused. */
    @Synchronized
    fun start() {
        if (!isLoaded || isRunning) return
        Native.start()
        isRunning = true
        isPaused = false
        pump = Pump().also { it.start() }
        audio?.start()
    }

    /** Pauses or resumes. Pausing also writes the in-game save to disk (see bridge.c). Sound drains within 120 ms. */
    @Synchronized
    fun setPaused(paused: Boolean) {
        if (!isLoaded) return
        Native.setPaused(paused)
        isPaused = paused
    }

    /**
     * Stops everything and unloads the game, ready for another [load]. Order matters and is kept here:
     * the picture thread first (the core frees its frame buffers), then the sound thread, then the core.
     */
    @Synchronized
    fun stop() {
        pump?.let {
            it.quit = true
            LockSupport.unpark(it)
            try { it.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (it.isAlive) Log.w("frame pump did not stop in time")
        }
        pump = null
        audio?.stop()
        synchronized(pulses) { pulses.fill(0) }
        if (isLoaded) {
            try { Native.stop() } catch (e: Throwable) { Log.e("stop", e) }
        }
        isLoaded = false
        isRunning = false
        isPaused = false
        fastForward = false
        linked = false
        held = 0
        coreLoaded = false
    }

    // ---- input, speed, volume ----------------------------------------------------------------

    @Volatile private var held = 0
    private val pulses = IntArray(10) // how many pulses hold each button right now (see [pulse])

    /** The keyboard's buttons as a mask of Native.A, Native.B, ... All of them, every time (not a change). */
    fun setButtons(mask: Int) {
        held = mask and 0x3FF
        pushKeys()
    }

    /** Lets go of every key (focus lost). */
    fun releaseButtons() = setButtons(0)

    /** Holds [mask] down for [ms], on top of the keyboard: a short press, or a soft reset. */
    fun pulse(mask: Int, ms: Long) {
        synchronized(pulses) { for (i in pulses.indices) if (mask and (1 shl i) != 0) pulses[i]++ }
        pushKeys()
        val t = Thread({
            try { Thread.sleep(ms) } catch (_: InterruptedException) {}
            synchronized(pulses) { for (i in pulses.indices) if (mask and (1 shl i) != 0 && pulses[i] > 0) pulses[i]-- }
            pushKeys()
        }, "key-pulse")
        t.isDaemon = true
        t.start()
    }

    /** Soft reset: A, B, Start and Select held together for 700 ms. */
    fun softReset() = pulse(Native.A or Native.B or Native.START or Native.SELECT, 700)

    private fun pushKeys() {
        if (!isLoaded) return
        var mask = held
        synchronized(pulses) { for (i in pulses.indices) if (pulses[i] > 0) mask = mask or (1 shl i) }
        Native.setKeys(mask)
    }

    /** 4x speed (sound is muted by the core meanwhile). Ignored while [linked]. */
    @Synchronized
    fun setFastForward(on: Boolean) {
        val v = on && !linked
        fastForward = v
        if (isLoaded) Native.setSpeed(if (v) FAST_FORWARD_SPEED else 1)
    }

    /** Tell the emulator a link room is (not) in use: fast forward goes off and cannot be turned on. */
    @Synchronized
    fun setLinked(on: Boolean) {
        linked = on
        if (on) setFastForward(false)
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
    }

    /** Silence (without touching [volume]), e.g. while the window is in the background. */
    fun mute(on: Boolean) {
        muted = on
        applyVolume()
    }

    private fun applyVolume() {
        if (isLoaded) Native.setVolume(if (muted) 0f else volume)
    }

    // ---- picture -----------------------------------------------------------------------------

    fun frameCount(): Long = if (isLoaded) Native.frameCount() else 0L

    /** The latest picture into [out] (240*160 ints, 0xFFRRGGBB). False if there is none yet. */
    fun copyFrame(out: IntArray): Boolean = isLoaded && Native.copyFrameRgb(out)

    /** A fresh copy of the latest picture, or null. */
    fun grabFrame(): IntArray? = IntArray(Native.WIDTH * Native.HEIGHT).takeIf { copyFrame(it) }

    /** Delivers the current picture to [frameSink] again (a new sink, or a paused game that must be redrawn). */
    fun refreshFrame() {
        pump?.force = true
    }

    private inner class Pump : Thread("frame-pump") {
        @Volatile var quit = false
        @Volatile var force = true // the first poll delivers whatever is there

        init { isDaemon = true }

        override fun run() {
            val bufs = Array(3) { IntArray(Native.WIDTH * Native.HEIGHT) }
            var next = 0
            var seen = -1L
            var lastPublish = 0L
            while (!quit) {
                try {
                    val n = Native.frameCount()
                    val now = System.nanoTime()
                    if ((n != seen && now - lastPublish >= MIN_PUBLISH_NS) || force) {
                        force = false
                        val buf = bufs[next]
                        if (Native.copyFrameRgb(buf)) {
                            seen = n
                            lastPublish = now
                            next = (next + 1) % bufs.size
                            frameSink?.invoke(buf)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("frame pump", e)
                }
                LockSupport.parkNanos(POLL_NS)
            }
        }
    }

    // ---- snapshots ---------------------------------------------------------------------------

    /** Saves the whole game state to [file] now, on the calling thread (blocks up to about 3 s). Not on the UI thread. */
    fun saveStateBlocking(file: File): Boolean {
        if (!isLoaded || !pathFits(file.absolutePath)) return false
        file.absoluteFile.parentFile?.mkdirs()
        return Native.saveState(file.absolutePath)
    }

    /** Loads a state saved by [saveStateBlocking], on the calling thread. Not on the UI thread. */
    fun loadStateBlocking(file: File): Boolean {
        if (!isLoaded || !file.isFile || !pathFits(file.absolutePath)) return false
        return Native.loadState(file.absolutePath)
    }

    /** [saveStateBlocking] on the autosave thread; [done] is called there with the outcome (always called). */
    fun saveState(file: File, done: ((Boolean) -> Unit)? = null) = onStateThread(done) { saveStateBlocking(file) }

    /** [loadStateBlocking] on the autosave thread; [done] is called there with the outcome (always called). */
    fun loadState(file: File, done: ((Boolean) -> Unit)? = null) = onStateThread(done) { loadStateBlocking(file) }

    private fun onStateThread(done: ((Boolean) -> Unit)?, work: () -> Boolean) {
        try {
            stateExec.execute {
                val ok = try { work() } catch (e: Throwable) { Log.e("state", e); false }
                done?.invoke(ok)
            }
        } catch (e: RejectedExecutionException) {
            done?.invoke(false)
        }
    }

    /** Queues [task] on the autosave thread (AutoSave's own work). Throws RejectedExecutionException after [shutdownStateThread]. */
    fun runOnStateThread(task: () -> Unit) = stateExec.execute(task)

    /** No more state work is taken; with [wait], gives a running one up to 1.5 s to finish. */
    fun shutdownStateThread(wait: Boolean = true) {
        stateExec.shutdown()
        if (!wait) return
        try { stateExec.awaitTermination(1500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    companion object {
        /** True while any Emulator has a game loaded (RomStore refuses to replace files then). */
        @Volatile var coreLoaded = false
            private set

        const val FAST_FORWARD_SPEED = 4

        private const val POLL_NS = 4_000_000L // frame counter poll
        private const val MIN_PUBLISH_NS = 8_000_000L // at 4x speed the UI still gets at most about 125 pictures a second

        private val windows = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("windows")

        /** mGBA copies paths into a 260-character buffer (Windows); longer ones would corrupt memory. */
        private fun pathFits(path: String) = !windows || path.length <= AppDirs.MAX_PATH_CHARS

        /** Null when the native library loads; otherwise a short reason (shown to the user, logged). */
        fun nativeProblem(): String? = try {
            Native.frameCount()
            null
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
