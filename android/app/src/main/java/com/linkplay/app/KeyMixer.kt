package com.linkplay.app

/**
 * Combines the touch buttons and gamepads into the key mask the GBA sees. Taps and
 * drags on the picture don't go through here: native code turns them into key
 * presses itself (Native.touchTap / touchSwipe).
 */
class KeyMixer {
    @Volatile var touch = 0
        set(v) { field = v; push() }
    @Volatile var pad = 0
        set(v) { field = v; push() }

    /** A short press (e.g. Back gesture = B). */
    fun pulse(key: Int) {
        Thread {
            touch = touch or key
            Thread.sleep(80)
            touch = touch and key.inv()
        }.start()
    }

    private fun push() = Native.setKeys(touch or pad)
}
