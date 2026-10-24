package com.linkplay.app

/**
 * The emulator core (mGBA + NetLink), implemented in native/bridge.c and built into libgbalink
 * (desktop/native/CMakeLists.txt). This is the desktop copy of the phone app's Native.kt: the JNI
 * symbols are the same (they are named after this class), so it has to stay in this package.
 *
 * Left out on purpose: the GLES drawing calls (gl*), which only exist on Android, and the touch
 * calls (touchTap, touchSwipe, touchBusy): the phone app never turns them on either.
 * Added for the desktop: copyFrameRgb and audioRead, because here the picture and the sound are
 * pulled by Java instead of being pushed to a GL surface and an AAudio stream.
 *
 * Threading rules (see bridge.c): load, saveState and loadState block, so call them off the UI
 * thread. Stop the thread that calls copyFrameRgb before stop(). audioRead may keep running
 * through stop() and load(). netWait and netPoll belong to one polling thread.
 */
object Native {
    init {
        System.loadLibrary("gbalink")
    }

    // GBA key bits, in KEYINPUT order.
    const val A = 1
    const val B = 2
    const val SELECT = 4
    const val START = 8
    const val RIGHT = 16
    const val LEFT = 32
    const val UP = 64
    const val DOWN = 128
    const val R = 256
    const val L = 512

    const val WIDTH = 240
    const val HEIGHT = 160

    /** Output sample rate of [audioRead]. */
    const val AUDIO_RATE = 48000

    /** Opens the ROM and its cartridge save. False (and nothing left half set up) on any failure. */
    @JvmStatic external fun load(romPath: String, savePath: String): Boolean
    @JvmStatic external fun start()
    @JvmStatic external fun setPaused(paused: Boolean)
    @JvmStatic external fun stop()
    @JvmStatic external fun setKeys(keys: Int)
    /** Emulation speed: 1 normal, 2, 4. Audio is muted above 1; ignored while the cable is attached. */
    @JvmStatic external fun setSpeed(factor: Int)
    @JvmStatic external fun setVolume(volume: Float)
    @JvmStatic external fun frameCount(): Long

    /** The latest picture as 0xFFRRGGBB ints, row by row (needs at least WIDTH * HEIGHT ints). */
    @JvmStatic external fun copyFrameRgb(out: IntArray): Boolean

    /**
     * Pulls [frames] frames of sound: interleaved stereo, signed 16-bit, [AUDIO_RATE] Hz, volume
     * applied. Always fills that many frames (zero-padded when the game has made less) and returns
     * how many were real sound. [out] needs room for frames * 2 shorts.
     */
    @JvmStatic external fun audioRead(out: ShortArray, frames: Int): Int

    // Snapshots of the whole emulator (autosave, save-state slots). Block until done, so call them
    // off the UI thread. The cartridge save is never part of a snapshot, so loading one can't roll
    // back a newer in-game save.
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean

    // Link play: attach the NetLink cable, then shuttle the room's link traffic in and out.
    @JvmStatic external fun netAttach(linkId: Int)
    @JvmStatic external fun netDetach()
    @JvmStatic external fun netSetLinkId(linkId: Int)
    @JvmStatic external fun netUnplug()
    @JvmStatic external fun netPush(frame: ByteArray)
    @JvmStatic external fun netPoll(): ByteArray?
    /** Blocks up to [timeoutMs] until link output is waiting for netPoll; true if there is some. */
    @JvmStatic external fun netWait(timeoutMs: Int): Boolean
}
