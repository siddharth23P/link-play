package com.linkplay.desktop.ui

import com.linkplay.desktop.Settings
import java.awt.event.KeyEvent

/** What the keyboard asks of the game. */
interface KeyActions {
    /** All the buttons held now, as a mask of Native.A, Native.B... */
    fun setButtons(mask: Int)
    fun setFastForward(on: Boolean)
    /** False while linked: the speed is the room's. */
    fun fastForwardAllowed(): Boolean
    fun fastForwardRefused()
    fun openMenu()
    fun loadSlot(n: Int)
    fun saveSlot(n: Int)
    fun toggleFullScreen()
    fun zoom(delta: Int)
}

/**
 * Turns key presses into what the game needs. Plain data in, plain calls out, so it can be checked
 * without a window: [press], [release] and [releaseAll] are all there is.
 *
 * Swing repeats a key while it is held: the second and later presses of a key are swallowed.
 * Letting go of the window (focus lost) must drop every key, or a button stays down for good.
 */
class KeyRouter(private val actions: KeyActions) {
    private val down = HashSet<Int>()
    private var mask = 0
    private var ffHeld = false
    private var ffLatched = false
    private var lastTabUp = -1_000_000_000L

    private fun maskNow(): Int {
        var m = 0
        for (c in down) Settings.buttonFor(c)?.let { m = m or it.bit }
        return m
    }

    private fun push() {
        val m = maskNow()
        if (m != mask) {
            mask = m
            actions.setButtons(m)
        }
    }

    private fun ffApply() {
        actions.setFastForward((ffHeld || ffLatched) && actions.fastForwardAllowed())
    }

    /** True when the key was ours (the event is then consumed). [shortcut]: Cmd (Mac) or Ctrl is held. */
    fun press(code: Int, shift: Boolean = false, shortcut: Boolean = false, nowNs: Long = System.nanoTime()): Boolean {
        if (shortcut) {
            when (code) {
                KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> { actions.zoom(1); return true }
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> { actions.zoom(-1); return true }
            }
            return false // Cmd+Q and the like belong to the system
        }
        val repeat = !down.add(code)
        when (code) {
            KeyEvent.VK_ESCAPE -> { if (!repeat) actions.openMenu(); return true }
            KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3 -> {
                if (!repeat) {
                    val n = code - KeyEvent.VK_F1 + 1
                    if (shift) actions.saveSlot(n) else actions.loadSlot(n)
                }
                return true
            }
            KeyEvent.VK_F11 -> { if (!repeat) actions.toggleFullScreen(); return true }
            KeyEvent.VK_TAB -> {
                if (repeat) return true
                if (!actions.fastForwardAllowed()) { actions.fastForwardRefused(); return true }
                if (ffLatched) { // any tap turns the latch off
                    ffLatched = false
                    ffHeld = false
                    lastTabUp = -1_000_000_000L
                } else {
                    ffHeld = true
                    // Pressed again soon after a release: keep it on.
                    if (nowNs - lastTabUp < DOUBLE_TAP_NS) ffLatched = true
                }
                ffApply()
                return true
            }
        }
        if (Settings.buttonFor(code) == null) {
            down.remove(code)
            return false
        }
        if (!repeat) push()
        return true
    }

    fun release(code: Int, nowNs: Long = System.nanoTime()): Boolean {
        if (!down.remove(code)) return false
        if (code == KeyEvent.VK_TAB) {
            if (ffHeld) lastTabUp = nowNs
            ffHeld = false
            ffApply()
            return true
        }
        push()
        return Settings.buttonFor(code) != null || code == KeyEvent.VK_ESCAPE || code in KeyEvent.VK_F1..KeyEvent.VK_F3 || code == KeyEvent.VK_F11
    }

    /** Focus lost, a dialog opened, the game stopped: every key is up. Fast forward ends too. */
    fun releaseAll() {
        down.clear()
        ffHeld = false
        ffLatched = false
        lastTabUp = -1_000_000_000L
        if (mask != 0) {
            mask = 0
            actions.setButtons(0)
        }
        actions.setFastForward(false)
    }

    /** The link started: fast forward is off for good, until it ends. */
    fun linkStarted() {
        ffHeld = false
        ffLatched = false
        actions.setFastForward(false)
    }

    companion object {
        const val DOUBLE_TAP_NS = 350_000_000L

        /**
         * A check of the rules above with a fake game: the lines are PASS or FAIL, for the snapshot run's report.
         * Uses the default key map, so it temporarily resets the keys.
         */
        fun selfCheck(): List<String> {
            val out = ArrayList<String>()
            fun check(name: String, ok: Boolean, detail: String = "") { out += (if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) ": $detail" else "" }
            val saved = Settings.keyMap()
            Settings.resetKeys()
            try {
                val buttons = ArrayList<Int>()
                val speeds = ArrayList<Boolean>()
                var allowed = true
                var refused = 0
                var menus = 0
                val slots = ArrayList<String>()
                val r = KeyRouter(object : KeyActions {
                    override fun setButtons(mask: Int) { buttons += mask }
                    override fun setFastForward(on: Boolean) { speeds += on }
                    override fun fastForwardAllowed() = allowed
                    override fun fastForwardRefused() { refused++ }
                    override fun openMenu() { menus++ }
                    override fun loadSlot(n: Int) { slots += "load$n" }
                    override fun saveSlot(n: Int) { slots += "save$n" }
                    override fun toggleFullScreen() {}
                    override fun zoom(delta: Int) {}
                })
                var t = 0L
                fun ms(n: Long) { t += n * 1_000_000L }
                val a = com.linkplay.app.Native.A
                val b = com.linkplay.app.Native.B
                val right = com.linkplay.app.Native.RIGHT

                r.press(KeyEvent.VK_X, nowNs = t)
                r.press(KeyEvent.VK_X, nowNs = t) // auto repeat
                check("key X presses A once", buttons == listOf(a), buttons.toString())
                r.press(KeyEvent.VK_RIGHT, nowNs = t)
                check("two keys make two buttons", buttons.last() == (a or right), buttons.toString())
                r.release(KeyEvent.VK_X, t)
                check("letting go of X leaves Right", buttons.last() == right, buttons.toString())
                r.releaseAll()
                check("focus lost drops every key", buttons.last() == 0, buttons.toString())
                buttons.clear()
                r.press(KeyEvent.VK_Z, nowNs = t)
                r.press(KeyEvent.VK_ESCAPE, nowNs = t)
                r.press(KeyEvent.VK_ESCAPE, nowNs = t)
                check("Escape opens the menu once (repeat swallowed)", menus == 1, "menus=$menus")
                r.releaseAll()
                check("Z is B", buttons.firstOrNull() == b, buttons.toString())
                check("a key nobody uses is not consumed", !r.press(KeyEvent.VK_Q, nowNs = t))
                r.press(KeyEvent.VK_F2, nowNs = t); r.release(KeyEvent.VK_F2, t)
                r.press(KeyEvent.VK_F3, shift = true, nowNs = t); r.release(KeyEvent.VK_F3, t)
                check("F2 loads slot 2, Shift+F3 saves slot 3", slots == listOf("load2", "save3"), slots.toString())

                speeds.clear()
                r.press(KeyEvent.VK_TAB, nowNs = t)
                check("holding Tab is fast forward", speeds.lastOrNull() == true, speeds.toString())
                ms(120); r.release(KeyEvent.VK_TAB, t)
                check("letting go ends it", speeds.lastOrNull() == false, speeds.toString())
                ms(200); r.press(KeyEvent.VK_TAB, nowNs = t)
                ms(100); r.release(KeyEvent.VK_TAB, t)
                check("a second tap within 350 ms keeps it on", speeds.lastOrNull() == true, speeds.toString())
                ms(900); r.press(KeyEvent.VK_TAB, nowNs = t)
                ms(60); r.release(KeyEvent.VK_TAB, t)
                check("any tap turns the latch off", speeds.lastOrNull() == false, speeds.toString())
                ms(900); r.press(KeyEvent.VK_TAB, nowNs = t); ms(50); r.release(KeyEvent.VK_TAB, t)
                ms(1200); r.press(KeyEvent.VK_TAB, nowNs = t); ms(50); r.release(KeyEvent.VK_TAB, t)
                check("taps more than 350 ms apart do not latch", speeds.lastOrNull() == false, speeds.toString())
                allowed = false
                speeds.clear()
                r.press(KeyEvent.VK_TAB, nowNs = t); r.release(KeyEvent.VK_TAB, t)
                check("no fast forward while linked, and it says so", refused == 1 && speeds.none { it }, "refused=$refused speeds=$speeds")
            } finally {
                Settings.resetKeys()
                for ((btn, code) in saved) if (code != btn.defaultKey) Settings.setKey(btn, code)
            }
            return out
        }
    }
}
