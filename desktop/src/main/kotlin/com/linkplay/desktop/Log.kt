package com.linkplay.desktop

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's log: `log.txt` in the data folder (about 1 MB, then it moves to `log.txt.old` and starts
 * again, so at most two files) and standard error. A packaged Windows app has no console, so the
 * file is what people can send when something goes wrong.
 *
 * Never log invite codes, reply codes or keys (they open a room). Log what happened, not the secret.
 */
object Log {
    const val TAG = "LinkPlay"
    private const val MAX_BYTES = 1L shl 20

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var writer: Writer? = null
    private var written = 0L

    /** Starts writing to [logFile] (appending). Before this, lines only go to standard error. */
    @Synchronized
    fun init(logFile: File = AppDirs.logFile) {
        closeFile()
        file = logFile
        try {
            logFile.parentFile?.mkdirs()
            written = if (logFile.isFile) logFile.length() else 0L
            if (written >= MAX_BYTES) rotate() else open()
        } catch (e: Exception) {
            System.err.println("$TAG: cannot open log file: ${e.message}")
        }
    }

    /** Flushes and closes the file (the process may carry on, logging to standard error only). */
    @Synchronized
    fun close() {
        closeFile()
        file = null
    }

    fun i(msg: String) = write("I", TAG, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(msg: String) = write("W", TAG, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(msg: String) = write("E", TAG, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)
    fun e(msg: String, t: Throwable) = write("E", TAG, "$msg: $t" + stackOf(t))

    private fun stackOf(t: Throwable): String =
        t.stackTrace.take(8).joinToString("") { "\n    at $it" } + (t.cause?.let { "\n  caused by $it" } ?: "")

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val line = "${stamp.format(Date())} $level/$tag: $msg"
        System.err.println(line)
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
            written += line.length + 1
            if (written >= MAX_BYTES) rotate()
        } catch (e: Exception) {
            closeFile() // disk full or the folder went away: carry on with standard error only
        }
    }

    private fun open() {
        val f = file ?: return
        writer = OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
    }

    /** log.txt becomes log.txt.old (replacing an older one) and a new log.txt starts. */
    private fun rotate() {
        val f = file ?: return
        closeFile()
        try {
            Files.move(f.toPath(), File(f.path + ".old").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            System.err.println("$TAG: cannot rotate log: ${e.message}")
            try { f.delete() } catch (_: Exception) {}
        }
        written = 0L
        try { open() } catch (e: Exception) { System.err.println("$TAG: cannot open log file: ${e.message}") }
    }

    private fun closeFile() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }
}
