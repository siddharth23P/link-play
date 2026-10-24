package com.linkplay.desktop

import com.linkplay.app.Native
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.Properties

/** The GBA's ten buttons, with the keyboard key each starts out on (AWT key codes). */
enum class Button(val id: String, val bit: Int, val label: String, val defaultKey: Int) {
    UP("up", Native.UP, "Up", 0x26), // VK_UP
    DOWN("down", Native.DOWN, "Down", 0x28), // VK_DOWN
    LEFT("left", Native.LEFT, "Left", 0x25), // VK_LEFT
    RIGHT("right", Native.RIGHT, "Right", 0x27), // VK_RIGHT
    A("a", Native.A, "A", 0x58), // VK_X
    B("b", Native.B, "B", 0x5A), // VK_Z
    L("l", Native.L, "L", 0x41), // VK_A
    R("r", Native.R, "R", 0x53), // VK_S
    START("start", Native.START, "Start", 0x0A), // VK_ENTER
    SELECT("select", Native.SELECT, "Select", 0x08), // VK_BACK_SPACE
}

/**
 * The app's preferences: `settings.properties` in the data folder. Every change is written at once
 * (the file is tiny), through a temporary file so a crash never leaves half a file behind.
 * Anything missing or unreadable falls back to its default.
 *
 * Keys: name, stun, filter, volume, scale, fullscreen, muteInBackground, keys.<button>.
 */
object Settings {
    /** Picture filters: nearest-neighbour, sharp (integer prescale, then smooth), smooth. */
    const val FILTER_PIXEL = 0
    const val FILTER_SHARP = 1
    const val FILTER_SMOOTH = 2
    const val DEFAULT_FILTER = FILTER_SHARP
    const val DEFAULT_VOLUME = 0.8f
    const val DEFAULT_SCALE = 3
    const val MAX_SCALE = 8

    private val props = Properties()
    private var loadedFrom: File? = null

    /** Reads the file (again). Call once at start after AppDirs.init; it also happens on first use. */
    @Synchronized
    fun load() {
        val f = AppDirs.settingsFile
        props.clear()
        loadedFrom = f
        if (!f.isFile) return
        try {
            InputStreamReader(Files.newInputStream(f.toPath()), Charsets.UTF_8).use { props.load(it) }
        } catch (e: Exception) {
            Log.w("settings: ${e.message}; using defaults")
            props.clear()
        }
    }

    @Synchronized
    private fun ready() {
        if (loadedFrom != AppDirs.settingsFile) load()
    }

    /** Writes the file now. Setters do this themselves; this is for the quit path to be sure. */
    @Synchronized
    fun save() {
        ready()
        val target = AppDirs.settingsFile
        val tmp = File(target.path + ".tmp")
        try {
            target.parentFile?.mkdirs()
            OutputStreamWriter(Files.newOutputStream(tmp.toPath()), Charsets.UTF_8).use {
                props.store(it, "Link Play settings")
            }
            moveReplacing(tmp, target)
        } catch (e: Exception) {
            Log.w("settings: could not save: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    // ---- values ------------------------------------------------------------------------------

    /** The player name as typed (trimmed); empty until set. */
    var name: String
        get() = str("name", "").trim()
        set(v) = put("name", v.trim())

    /** What other players see: the name, or "Player". */
    val playerName: String get() = name.ifBlank { "Player" }

    /** Ask a STUN server for the public address (Advanced). Off by default. */
    var stun: Boolean
        get() = bool("stun", false)
        set(v) = put("stun", v.toString())

    /** [FILTER_PIXEL], [FILTER_SHARP] or [FILTER_SMOOTH]. */
    var filter: Int
        get() = int("filter", DEFAULT_FILTER).coerceIn(FILTER_PIXEL, FILTER_SMOOTH)
        set(v) = put("filter", v.coerceIn(FILTER_PIXEL, FILTER_SMOOTH).toString())

    /** 0.0 .. 1.0. */
    var volume: Float
        get() = flt("volume", DEFAULT_VOLUME).coerceIn(0f, 1f)
        set(v) = put("volume", v.coerceIn(0f, 1f).toString())

    /** Window size as a multiple of the 240x160 picture. */
    var scale: Int
        get() = int("scale", DEFAULT_SCALE).coerceIn(1, MAX_SCALE)
        set(v) = put("scale", v.coerceIn(1, MAX_SCALE).toString())

    var fullscreen: Boolean
        get() = bool("fullscreen", false)
        set(v) = put("fullscreen", v.toString())

    /** Mute (and, when not linked, pause) while the window is in the background. Off by default. */
    var muteInBackground: Boolean
        get() = bool("muteInBackground", false)
        set(v) = put("muteInBackground", v.toString())

    // ---- keyboard ----------------------------------------------------------------------------

    /** The AWT key code for [button]. */
    fun key(button: Button): Int = int("keys.${button.id}", button.defaultKey).takeIf { it > 0 } ?: button.defaultKey

    fun setKey(button: Button, keyCode: Int) = put("keys.${button.id}", keyCode.toString())

    /** Every button back on its default key. */
    @Synchronized
    fun resetKeys() {
        ready()
        for (b in Button.entries) props.remove("keys.${b.id}")
        save()
    }

    /** All ten buttons with their keys, e.g. for the Controls dialog. */
    fun keyMap(): Map<Button, Int> = Button.entries.associateWith { key(it) }

    /** The button on [keyCode], or null. If two buttons share a key (hand-edited file) the first wins. */
    fun buttonFor(keyCode: Int): Button? = Button.entries.firstOrNull { key(it) == keyCode }

    // ---- plumbing ----------------------------------------------------------------------------

    @Synchronized private fun str(k: String, def: String): String { ready(); return props.getProperty(k, def) }
    private fun int(k: String, def: Int): Int = str(k, "").toIntOrNull() ?: def
    private fun flt(k: String, def: Float): Float = str(k, "").toFloatOrNull()?.takeIf { it.isFinite() } ?: def
    private fun bool(k: String, def: Boolean): Boolean = when (str(k, "")) { "true" -> true; "false" -> false; else -> def }

    @Synchronized
    private fun put(k: String, v: String) {
        ready()
        if (props.getProperty(k) == v) return
        props.setProperty(k, v)
        save()
    }
}
