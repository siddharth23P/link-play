package com.linkplay.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The ROM's identity. Rooms only accept players whose ROM has the same SHA-1. */
data class RomInfo(val sha1: String, val title: String, val code: String, val size: Long, val path: String) {
    val shortHash get() = sha1.take(12)
}

object RomStore {
    private const val PREFS = "rom"

    /** Copies the picked ROM into app storage, hashing it on the way. */
    fun import(context: Context, uri: Uri): RomInfo {
        val dir = File(context.filesDir, "roms").apply { mkdirs() }
        val tmp = File(dir, "import.tmp")
        val digest = MessageDigest.getInstance("SHA-1")
        val header = ByteArray(0xC0)
        var size = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the file." }
            tmp.outputStream().use { out ->
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
                }
            }
        }
        require(size in 0xC0..(64L shl 20)) { "That doesn't look like a GBA ROM (wrong size)." }
        require(header[0xB2] == 0x96.toByte()) { "That doesn't look like a GBA ROM (bad header)." }
        val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
        val rom = File(dir, "$sha1.gba")
        // A different ROM: the old one's snapshots would never match.
        val previous = context.getSharedPreferences(PREFS, 0).getString("sha1", null)
        if (previous != null && previous != sha1) clearSnapshots(context, previous)
        if (!tmp.renameTo(rom)) tmp.copyTo(rom, overwrite = true).also { tmp.delete() }
        val info = RomInfo(
            sha1 = sha1,
            title = String(header, 0xA0, 12, Charsets.ISO_8859_1).trimEnd('\u0000').trim(),
            code = String(header, 0xAC, 4, Charsets.ISO_8859_1),
            size = size,
            path = rom.absolutePath,
        )
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("sha1", info.sha1).putString("title", info.title).putString("code", info.code)
            .putLong("size", info.size).putString("path", info.path).apply()
        // Keep only the current ROM.
        dir.listFiles()?.filter { it.name.endsWith(".gba") && it != rom }?.forEach { it.delete() }
        return info
    }

    fun current(context: Context): RomInfo? {
        val p = context.getSharedPreferences(PREFS, 0)
        val path = p.getString("path", null) ?: return null
        if (!File(path).exists()) return null
        return RomInfo(p.getString("sha1", "")!!, p.getString("title", "")!!, p.getString("code", "")!!, p.getLong("size", 0), path)
    }

    /** One save file per ROM, so a different ROM never overwrites your progress. */
    fun saveFile(context: Context, rom: RomInfo): File =
        File(File(context.filesDir, "saves").apply { mkdirs() }, "${rom.sha1}.sav")

    /** Where a ROM's snapshots live (see AutoSave). */
    fun statesDir(context: Context, sha1: String) = File(File(context.filesDir, "states"), sha1)

    fun clearSnapshots(context: Context, sha1: String) {
        statesDir(context, sha1).deleteRecursively()
    }

    fun importSave(context: Context, rom: RomInfo, uri: Uri) {
        clearSnapshots(context, rom.sha1) // they would not match the imported save
        val target = saveFile(context, rom)
        // The save being replaced stays next to it, in case the wrong file was picked.
        if (target.length() > 0) target.copyTo(File(target.parentFile, "${target.name}.bak"), overwrite = true)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the file." }
            target.outputStream().use { input.copyTo(it) }
        }
    }

    /** Copies the ROM's in-game save out: a backup, or to carry it to another phone or app. */
    fun exportSave(context: Context, rom: RomInfo, uri: Uri) {
        val source = saveFile(context, rom)
        require(source.length() > 0) { "No in-game save yet: save in the game first." }
        context.contentResolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out) { "Could not write the file." }
            source.inputStream().use { it.copyTo(out) }
        }
    }

    /** A file name for an exported save, e.g. "PKM QUETZAL 2026-10-21 1830.sav". */
    fun exportName(rom: RomInfo): String {
        val title = rom.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "game" }
        return "$title ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())}.sav"
    }
}
