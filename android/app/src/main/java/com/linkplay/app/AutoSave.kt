package com.linkplay.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot policy. A snapshot of the running game is taken every few minutes of play, when the app goes to
 * the background or the lobby, and right before the link cable is attached (never while linked). Native
 * save/load block for a moment, so they all run on one background thread; the callbacks come from it too.
 * Snapshots never include the cartridge save (see bridge.c), so restoring one cannot roll back in-game saves.
 */
class AutoSave(private val context: Context, private val sha1: () -> String?) {
    enum class Kind { AUTO, BEFORE_LINK }

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "autosave").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val playMs = AtomicLong() // running time since the game booted or a snapshot was loaded (UI thread)
    private val sinceSaveMs = AtomicLong()

    private fun dir(): File? = sha1()?.let { RomStore.statesDir(context, it) }
    private fun file(name: String): File? = dir()?.let { File(it, name) }

    fun autoFile(): File? = file("auto.ss")?.takeIf { it.isFile } ?: file("auto-prev.ss")?.takeIf { it.isFile }
    fun beforeLinkFile(): File? = file("before-link.ss")?.takeIf { it.isFile }

    /** The newest of the autosave and the before-link snapshot. */
    fun newest(): File? = listOfNotNull(autoFile(), beforeLinkFile()).maxByOrNull { it.lastModified() }

    /**
     * The newest snapshot taken with the in-game save as it is now. A snapshot from before a newer in-game
     * save is never resumed automatically: saving in the game afterwards would replace that newer save.
     */
    fun newestUsable(): File? = listOfNotNull(autoFile(), beforeLinkFile()).filter { matchesSave(it) }.maxByOrNull { it.lastModified() }

    /** Fingerprint of the in-game save: its data, without the 16-byte clock footer mGBA appends. */
    fun saveFingerprint(): String? {
        val sha = sha1() ?: return null
        val f = File(File(context.filesDir, "saves"), "$sha.sav")
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            val n = if (bytes.size % 1024 == 16) bytes.size - 16 else bytes.size
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(bytes, 0, n)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Records which in-game save [snapshot] was taken with (Native.saveState flushes it to disk first). */
    fun stampSave(snapshot: File) {
        val fp = saveFingerprint() ?: return
        try { File(snapshot.path + SAVE_STAMP).writeText(fp) } catch (_: Exception) {}
    }

    /** True when the in-game save hasn't changed since [snapshot] was taken. Unknown counts as changed. */
    fun matchesSave(snapshot: File): Boolean {
        val stamp = File(snapshot.path + SAVE_STAMP)
        return try { stamp.isFile && stamp.readText() == saveFingerprint() } catch (_: Exception) { false }
    }

    private fun moveWithStamp(from: File, to: File): Boolean {
        val ok = from.renameTo(to)
        val stamp = File(from.path + SAVE_STAMP)
        if (ok && stamp.exists()) stamp.renameTo(File(to.path + SAVE_STAMP)) else if (ok) File(to.path + SAVE_STAMP).delete()
        return ok
    }

    /** The game booted or a snapshot was loaded: nothing is saved for the next two minutes. */
    fun booted() {
        playMs.set(0)
        sinceSaveMs.set(0)
    }

    /** Called every [dtMs] from the UI; true when a periodic snapshot is due. */
    fun tick(dtMs: Long, running: Boolean, linked: Boolean): Boolean {
        if (!running) return false
        val play = playMs.addAndGet(dtMs)
        val since = sinceSaveMs.addAndGet(dtMs)
        return !linked && play >= GUARD_MS && since >= EVERY_MS
    }

    /** "3 min ago" for a snapshot file. */
    fun agoText(f: File): String {
        val m = (System.currentTimeMillis() - f.lastModified()) / 60000
        return when {
            m < 1 -> "less than a minute ago"
            m < 120 -> "$m min ago"
            else -> "${m / 60} h ago"
        }
    }

    /**
     * Saves a snapshot unless the game is too fresh (or, for [Kind.AUTO], another snapshot is running).
     * [done] gets the outcome, on the background thread; it is always called.
     */
    fun snapshot(kind: Kind, done: ((Boolean) -> Unit)? = null) {
        val d = dir()
        // Right before a link the snapshot is always taken (however fresh the game): "go back" must never
        // offer an older session's file. The periodic one waits for the guard time.
        if (d == null || (kind == Kind.AUTO && (playMs.get() < GUARD_MS || sinceSaveMs.get() < TICK_MS || !busy.compareAndSet(false, true)))) {
            done?.invoke(false)
            return
        }
        if (kind == Kind.BEFORE_LINK) busy.set(true)
        try {
        exec.execute {
            var ok = false
            try {
                d.mkdirs()
                if (kind == Kind.BEFORE_LINK) {
                    val bl = File(d, "before-link.ss")
                    ok = Native.saveState(bl.absolutePath)
                    if (ok) stampSave(bl) else { bl.delete(); File(bl.path + SAVE_STAMP).delete() } // never leave a stale one that "go back" would load
                } else {
                    val next = File(d, "auto-new.ss")
                    ok = Native.saveState(next.absolutePath)
                    if (ok) {
                        stampSave(next)
                        val cur = File(d, "auto.ss")
                        if (cur.exists()) moveWithStamp(cur, File(d, "auto-prev.ss"))
                        ok = moveWithStamp(next, cur)
                    } else next.delete()
                }
            } catch (e: Throwable) {
                Log.w(PhoneLink.TAG, "autosave: ${e.message}")
            } finally {
                busy.set(false)
            }
            Log.i(PhoneLink.TAG, "autosave: $kind ${if (ok) "saved" else "skipped or failed"}")
            if (ok && kind == Kind.AUTO) sinceSaveMs.set(0)
            done?.invoke(ok)
        }
        } catch (e: RejectedExecutionException) {
            busy.set(false)
            done?.invoke(false)
        }
    }

    fun load(f: File, done: (Boolean) -> Unit) {
        try {
            exec.execute {
                val ok = try { Native.loadState(f.absolutePath) } catch (e: Throwable) { false }
                done(ok)
            }
        } catch (e: RejectedExecutionException) {
            done(false)
        }
    }

    /** Waits briefly for a running snapshot, so Native.stop doesn't pull the game out from under it. */
    fun shutdown(wait: Boolean = true) {
        exec.shutdown()
        if (!wait) return
        try { exec.awaitTermination(1500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    companion object {
        const val GUARD_MS = 2 * 60_000L
        const val EVERY_MS = 5 * 60_000L
        const val TICK_MS = 10_000L
        /** Next to each snapshot: the fingerprint of the in-game save it was taken with. */
        const val SAVE_STAMP = ".save"
    }
}
