package com.linkplay.desktop

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Properties

/** The ROM's identity. Rooms only accept players whose ROM has the same SHA-1. */
data class RomInfo(val sha1: String, val title: String, val code: String, val size: Long, val path: String) {
    val shortHash get() = sha1.take(12)
    val file get() = File(path)
}

/**
 * The ROM, its save and its snapshots, in the data folder (see AppDirs). The desktop port of the phone
 * app's RomStore: same checks, same file names, same rules, but with plain files in place of Uris.
 *
 * Windows keeps a file open and mapped for as long as the game has it, so replacing the ROM or the
 * save under a running game is refused here: stop the Emulator first (the phone app refuses the
 * same way, with "restart the app"). Exporting a copy is fine at any time.
 */
object RomStore {
    private const val MAX_ROM = 64L shl 20

    /** Real GBA saves are 512 B to 128 KiB (plus mGBA's 16-byte clock footer); variants exist, so only a ceiling. */
    private const val MAX_SAVE = 256L shl 10

    /** How many `.sav.<stamp>.bak` copies of replaced saves are kept per ROM. */
    private const val KEEP_BACKUPS = 5

    private const val MSG_ROM_SIZE = "That doesn't look like a GBA ROM (wrong size)."
    private const val MSG_ROM_HEADER = "That doesn't look like a GBA ROM (bad header)."
    private const val MSG_OPEN = "Could not open the file."
    private const val MSG_SAVE_SIZE = "That doesn't look like a GBA save (wrong size)."

    private val SHA1 = Regex("[0-9a-f]{40}")
    private val BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** The snapshots a replaced save makes stale: autosaves, the before-link one, the lobby backdrop (and their stamps / temp files). Never `slot*`. */
    private val AUTO_SNAPSHOT = Regex("(auto(-prev|-new)?\\.ss|before-link\\.ss)(\\.save|\\.tmp)?|last-frame\\.png(\\.tmp)?")

    /**
     * Looks at [source] without copying it: null when it could be a GBA ROM, else a message fit to show
     * the user (the picker asks before it stops the running game). [import] checks again while it copies
     * (the file may change in between), so this is only a courtesy.
     */
    fun check(source: File): String? {
        val header = ByteArray(0xC0)
        val read = try {
            FileInputStream(source).use { it.readNBytes(header, 0, header.size) }
        } catch (e: IOException) {
            return MSG_OPEN
        }
        if (read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            return "Zip files aren't supported. Unzip the ROM first."
        }
        val size = source.length()
        if (size !in 0xC0..MAX_ROM || read < header.size) return MSG_ROM_SIZE
        if (header[0xB2] != 0x96.toByte()) return MSG_ROM_HEADER
        return null
    }

    /**
     * Copies the picked ROM into the data folder, hashing it on the way. Throws
     * IllegalArgumentException with a message fit to show to the user when the file is no GBA ROM,
     * and IllegalStateException when a game is still loaded.
     */
    @JvmName("importRom") // `import` is a keyword in Java
    fun import(source: File): RomInfo {
        check(!Emulator.coreLoaded) { "Close the game before changing the ROM." }
        val dir = AppDirs.romsDir
        val tmp = File(dir, "import.tmp")
        val digest = MessageDigest.getInstance("SHA-1")
        val header = ByteArray(0xC0)
        var size = 0L
        try {
            val input = try {
                FileInputStream(source)
            } catch (e: IOException) {
                throw IllegalArgumentException(MSG_OPEN)
            }
            input.use {
                Files.newOutputStream(tmp.toPath()).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (size < header.size) {
                            System.arraycopy(buf, 0, header, size.toInt(), minOf(n.toLong(), header.size - size).toInt())
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        size += n
                        // Don't copy gigabytes just to say no (a picked video, say).
                        require(size <= MAX_ROM) { MSG_ROM_SIZE }
                    }
                }
            }
            require(size in 0xC0..MAX_ROM) { MSG_ROM_SIZE }
            require(header[0xB2] == 0x96.toByte()) { MSG_ROM_HEADER }
        } catch (e: Throwable) {
            tmp.delete() // a rejected file leaves nothing behind (up to 64 MiB otherwise)
            throw e
        }
        val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
        val rom = File(dir, "$sha1.gba")
        // A different ROM: the old one's snapshots stay. They are keyed by the ROM's SHA-1 (and stamped with
        // the save they were taken with), so they never match the new ROM and become usable again if the old
        // ROM comes back. They are small, and wiping them on every ROM change made progress look lost.
        moveReplacing(tmp, rom)
        val info = RomInfo(
            sha1 = sha1,
            title = String(header, 0xA0, 12, Charsets.ISO_8859_1).trimEnd('\u0000').trim(),
            code = String(header, 0xAC, 4, Charsets.ISO_8859_1),
            size = size,
            path = rom.absolutePath,
        )
        writeState(info)
        // Keep only the current ROM.
        dir.listFiles()?.filter { it.name.endsWith(".gba") && it != rom }?.forEach { it.delete() }
        return info
    }

    /** The imported ROM, or null when there is none (or its file is gone). */
    fun current(): RomInfo? {
        val s = readState() ?: return null
        val file = File(AppDirs.romsDir, "${s.sha1}.gba")
        if (!file.isFile) return null
        return RomInfo(s.sha1, s.title, s.code, s.size, file.absolutePath)
    }

    /** One save file per ROM, so a different ROM never overwrites your progress. */
    fun saveFile(rom: RomInfo): File = File(AppDirs.savesDir, "${rom.sha1}.sav")

    /** True when the game has written an in-game save. */
    fun hasSave(rom: RomInfo): Boolean = saveFile(rom).length() > 0

    /** Where a ROM's snapshots live (see AutoSave). */
    fun statesDir(sha1: String): File = AppDirs.statesDir(sha1)

    /** A sha1 that cannot reach outside the states folder (blank, or with a separator or a dot). */
    private fun safeKey(sha1: String) = sha1.isNotBlank() && sha1.none { it == '/' || it == '\\' || it == '.' }

    /** Deletes everything kept for a ROM, slots included. Not used on a ROM change (see [import]). */
    fun clearSnapshots(sha1: String) {
        if (!safeKey(sha1)) return // never the whole states folder
        statesDir(sha1).deleteRecursively()
    }

    /**
     * Deletes only the ROM's automatic snapshots (`auto*.ss`, `before-link.ss`, their stamps, and the lobby
     * backdrop `last-frame.png`). The slots (`slot*.ss`, `slot*.png`) are the user's own and stay.
     */
    fun clearAutoSnapshots(sha1: String) {
        if (!safeKey(sha1)) return
        statesDir(sha1).listFiles()?.filter { it.isFile && AUTO_SNAPSHOT.matches(it.name) }?.forEach { it.delete() }
    }

    /**
     * Replaces the in-game save with [source]. Throws IllegalArgumentException with a message fit to show
     * to the user (not a GBA save, unreadable) and IllegalStateException when a game is still loaded.
     *
     * Order, so that a failure costs nothing: [source] is checked (1 byte to 256 KiB; exact GBA sizes are
     * not enforced, variants exist) and copied next to the save first; only then is the save it replaces
     * copied to `<name>.sav.<yyyyMMdd-HHmmss>.bak` in the same folder (a backup is never overwritten, the
     * newest 5 per ROM are kept), the copy moved into place, and last the automatic snapshots dropped
     * ([clearAutoSnapshots]): they were taken with the old save and would no longer match. Manual slots stay;
     * loading one that is older than the save already asks first. The caller stops the game first.
     */
    fun importSave(rom: RomInfo, source: File) {
        check(!Emulator.coreLoaded) { "Close the game before importing a save." }
        if (!source.isFile || !source.canRead()) throw IllegalArgumentException(MSG_OPEN)
        require(source.length() in 1..MAX_SAVE) { MSG_SAVE_SIZE }
        val target = saveFile(rom)
        val tmp = File(target.path + ".tmp")
        try {
            // Copy first, replace after: a failed copy must not cost the save it was meant to replace.
            try {
                Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                throw IllegalArgumentException(MSG_OPEN)
            }
            require(tmp.length() in 1..MAX_SAVE) { MSG_SAVE_SIZE } // the file may have changed since the first look
            if (target.length() > 0) {
                try {
                    Files.copy(target.toPath(), backupFile(target).toPath())
                } catch (e: IOException) {
                    throw IllegalArgumentException("Could not back up your current save, so it was left as it is.")
                }
            }
            moveReplacing(tmp, target)
        } finally {
            tmp.delete()
        }
        clearAutoSnapshots(rom.sha1)
        pruneBackups(target)
    }

    /** The backups of [target]'s save, oldest first (only files named exactly like ours; the stamp sorts by time). */
    private fun backupsOf(target: File): List<File> {
        val pattern = Regex(Regex.escape(target.name) + "\\.(\\d{8}-\\d{6})\\.bak")
        return target.parentFile?.listFiles()?.filter { it.isFile && pattern.matches(it.name) }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * `<save>.<yyyyMMdd-HHmmss>.bak` beside [target]. Never an existing name, and always sorting after the
     * ones there (two imports within a second move on by a second), so pruning drops the oldest.
     */
    private fun backupFile(target: File): File {
        var at = LocalDateTime.now()
        backupsOf(target).lastOrNull()?.let { newest ->
            val stamp = newest.name.removePrefix(target.name + ".").removeSuffix(".bak")
            try {
                val last = LocalDateTime.parse(stamp, BACKUP_STAMP)
                if (!last.isBefore(at)) at = last.plusSeconds(1)
            } catch (_: Exception) {}
        }
        while (true) {
            val f = File(target.parentFile, "${target.name}.${BACKUP_STAMP.format(at)}.bak")
            if (!f.exists()) return f
            at = at.plusSeconds(1)
        }
    }

    /** Keeps the newest [KEEP_BACKUPS] backups of this save. */
    private fun pruneBackups(target: File) {
        try {
            backupsOf(target).dropLast(KEEP_BACKUPS).forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("save backups: ${e.message}")
        }
    }

    /** Copies the ROM's in-game save out: a backup, or to carry it to another device. */
    fun exportSave(rom: RomInfo, dest: File) {
        val source = saveFile(rom)
        require(source.length() > 0) { "No in-game save yet: save in the game first." }
        try {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IllegalArgumentException("Could not write the file.")
        }
    }

    /** A file name for an exported save, e.g. "PKM QUETZAL 2026-10-21 1830.sav". */
    fun exportName(rom: RomInfo): String {
        val title = rom.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "game" }
        return "$title ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())}.sav"
    }

    // ---- what is remembered about the ROM (rom.properties) ------------------------------------

    private class State(val sha1: String, val title: String, val code: String, val size: Long)

    private fun readState(): State? {
        val f = AppDirs.romFile
        if (!f.isFile) return null
        return try {
            val p = Properties()
            InputStreamReader(Files.newInputStream(f.toPath()), Charsets.UTF_8).use { p.load(it) }
            val sha1 = p.getProperty("sha1") ?: return null
            if (!SHA1.matches(sha1)) return null // hand-edited: never build a path out of it
            State(sha1, p.getProperty("title", ""), p.getProperty("code", ""), p.getProperty("size")?.toLongOrNull() ?: 0L)
        } catch (e: Exception) {
            Log.w("rom.properties: ${e.message}")
            null
        }
    }

    private fun writeState(info: RomInfo) {
        val f = AppDirs.romFile
        val tmp = File(f.path + ".tmp")
        val p = Properties()
        p.setProperty("sha1", info.sha1)
        p.setProperty("title", info.title)
        p.setProperty("code", info.code)
        p.setProperty("size", info.size.toString())
        OutputStreamWriter(Files.newOutputStream(tmp.toPath()), Charsets.UTF_8).use { p.store(it, "Link Play ROM") }
        moveReplacing(tmp, f)
    }
}
