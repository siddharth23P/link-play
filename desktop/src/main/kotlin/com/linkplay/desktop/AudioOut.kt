package com.linkplay.desktop

import com.linkplay.app.Native
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Sound out. The core keeps a small ring of finished sound (about 100 ms at most); this thread pulls
 * it 512 frames at a time (Native.audioRead: 48 kHz, stereo, 16-bit, volume already applied) and
 * writes it to a javax.sound line. The blocking write is the thread's clock. The emulator itself
 * does not follow the sound card: it runs on its own steady clock like the phone app, so a Bluetooth
 * speaker with an odd clock cannot slow a link game down. A clock that drifts shows up as an
 * occasional click, as on the phone.
 *
 * With no sound device (or one that refuses to open) the thread still pulls at real-time pace and
 * throws the sound away, so the ring never grows stale. A device that shows up later is not picked
 * up until the game is started again.
 *
 * The thread keeps pulling while the game is paused: it gets silence and drains the ring in 120 ms.
 */
class AudioOut {
    @Volatile private var thread: Thread? = null
    @Volatile private var quit = false
    @Volatile private var line: SourceDataLine? = null

    /** True once a sound device is open and playing. False means silent mode (also before [start] finished opening). */
    @Volatile var hasDevice = false
        private set

    /** Why there is no device, when there is none. */
    @Volatile var problem: String? = null
        private set

    private val pulled = AtomicLong()
    private val real = AtomicLong()
    private val shortCount = AtomicLong()

    /** Frames asked for from the core so far. */
    val framesRequested: Long get() = pulled.get()

    /** Of those, frames that were real sound (the rest was zero padding). */
    val framesReal: Long get() = real.get()

    /** Pulls that came back short: the game had less sound ready than the card wanted (a click). */
    val shortPulls: Long get() = shortCount.get()

    @Synchronized
    fun start() {
        if (thread != null) return
        quit = false
        hasDevice = false
        problem = null
        pulled.set(0)
        real.set(0)
        shortCount.set(0)
        val t = Thread(::run, "audio-out")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY - 1
        thread = t
        t.start()
    }

    /** Stops the thread and closes the device. Safe to call twice. */
    @Synchronized
    fun stop() {
        val t = thread ?: return
        quit = true
        // A write blocked on a device that stopped answering must not hang the app: stop and flush
        // the line first (that wakes the write), then wait a moment for the thread.
        val l = line
        try { l?.stop() } catch (_: Exception) {}
        try { l?.flush() } catch (_: Exception) {}
        LockSupport.unpark(t)
        try { t.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (t.isAlive) Log.w("audio thread did not stop in time")
        try { line?.close() } catch (_: Exception) {}
        line = null
        thread = null
        hasDevice = false
    }

    private fun run() {
        val l = open()
        line = l
        if (quit) {
            try { l?.close() } catch (_: Exception) {}
            return
        }
        hasDevice = l != null
        val shorts = ShortArray(BLOCK_FRAMES * 2)
        val bytes = ByteArray(BLOCK_FRAMES * 4)
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var next = System.nanoTime()
        while (!quit) {
            val n = try {
                Native.audioRead(shorts, BLOCK_FRAMES)
            } catch (e: Throwable) {
                Log.e("audioRead", e)
                break
            }
            pulled.addAndGet(BLOCK_FRAMES.toLong())
            real.addAndGet(n.toLong())
            if (n < BLOCK_FRAMES && n > 0) shortCount.incrementAndGet()
            if (l != null) {
                view.clear()
                view.put(shorts)
                var off = 0
                while (off < bytes.size && !quit) {
                    val w = try { l.write(bytes, off, bytes.size - off) } catch (e: Exception) { -1 }
                    if (w <= 0) {
                        if (quit) break
                        Log.w("audio write failed; going silent")
                        break
                    }
                    off += w
                }
                if (off < bytes.size && !quit) { // the device failed: carry on without it
                    try { l.close() } catch (_: Exception) {}
                    line = null
                    hasDevice = false
                    problem = "The sound device stopped working."
                    silentLoop(shorts, next)
                    return
                }
            } else {
                next += BLOCK_NS
                val wait = next - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else if (wait < -100 * BLOCK_NS) next = System.nanoTime()
            }
        }
    }

    /** Real-time pace, sound discarded. */
    private fun silentLoop(shorts: ShortArray, start: Long) {
        var next = start
        while (!quit) {
            try { Native.audioRead(shorts, BLOCK_FRAMES) } catch (e: Throwable) { return }
            next += BLOCK_NS
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait) else if (wait < -100 * BLOCK_NS) next = System.nanoTime()
        }
    }

    private fun open(): SourceDataLine? {
        val fmt = AudioFormat(Native.AUDIO_RATE.toFloat(), 16, 2, true, false) // signed, little-endian
        return try {
            // For checks and for a broken sound driver: java -Dlinkplay.noaudio=true ...
            if (System.getProperty("linkplay.noaudio") != null) throw IllegalStateException("sound is switched off (linkplay.noaudio)")
            val l = AudioSystem.getSourceDataLine(fmt)
            // About 80 ms: enough that a GC pause or a busy EDT cannot starve it, short enough to feel
            // in step with the picture.
            l.open(fmt, LINE_FRAMES * 4)
            l.start()
            Log.i("audio: ${l.format.sampleRate.toInt()} Hz, buffer ${l.bufferSize / 4} frames")
            l
        } catch (e: Throwable) {
            // No device, device busy, headless CI: the game runs without sound.
            problem = e.message ?: e.javaClass.simpleName
            Log.w("audio: no sound device (${problem})")
            null
        }
    }

    companion object {
        /** Frames per pull: 10.7 ms. */
        const val BLOCK_FRAMES = 512
        private const val BLOCK_NS = BLOCK_FRAMES * 1_000_000_000L / Native.AUDIO_RATE
        /** Requested device buffer: 80 ms. */
        const val LINE_FRAMES = 3840
    }
}
