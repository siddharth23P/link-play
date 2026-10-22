package com.linkplay.app

/** The emulator core (mGBA + NetLink), implemented in src/main/cpp/bridge.c. */
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

    @JvmStatic external fun load(romPath: String, savePath: String): Boolean
    @JvmStatic external fun start()
    @JvmStatic external fun setPaused(paused: Boolean)
    @JvmStatic external fun stop()
    @JvmStatic external fun setKeys(keys: Int)
    /** Emulation speed: 1 normal, 2, 4. Audio is muted above 1; ignored while the cable is attached. */
    @JvmStatic external fun setSpeed(factor: Int)
    @JvmStatic external fun setVolume(volume: Float)
    @JvmStatic external fun frameCount(): Long
    @JvmStatic external fun copyFrame(out: ShortArray): Boolean

    // Snapshots of the whole emulator (autosave). Block until done, so call them off the UI
    // thread. The cartridge save is never part of a snapshot, so loading one can't roll back
    // a newer in-game save.
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean

    // Touch navigation (native/touchnav.c, runs on the emulation thread): a tap steers
    // the menu cursor onto that spot and presses A; swipes are D-pad steps. While it
    // works, its keys replace setKeys'.
    const val SWIPE_UP = 0
    const val SWIPE_DOWN = 1
    const val SWIPE_LEFT = 2
    const val SWIPE_RIGHT = 3
    @JvmStatic external fun touchTap(x: Int, y: Int)
    @JvmStatic external fun touchSwipe(dir: Int, steps: Int)
    @JvmStatic external fun touchBusy(): Boolean

    // Link play: attach the NetLink cable, then shuttle the room's link traffic in and out.
    @JvmStatic external fun netAttach(linkId: Int)
    @JvmStatic external fun netDetach()
    @JvmStatic external fun netSetLinkId(linkId: Int)
    @JvmStatic external fun netUnplug()
    @JvmStatic external fun netPush(frame: ByteArray)
    @JvmStatic external fun netPoll(): ByteArray?
    /** Blocks up to [timeoutMs] until link output is waiting for netPoll; true if there is some. */
    @JvmStatic external fun netWait(timeoutMs: Int): Boolean

    // Rendering, called on the GL thread.
    @JvmStatic external fun glInit()
    @JvmStatic external fun glResize(width: Int, height: Int)
    @JvmStatic external fun glDraw(filter: Int)
    @JvmStatic external fun glSetPictureRect(left: Int, top: Int, width: Int, height: Int)
    @JvmStatic external fun glSetAmbient(on: Boolean)
}
