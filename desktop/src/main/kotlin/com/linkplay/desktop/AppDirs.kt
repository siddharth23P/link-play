package com.linkplay.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Where Link Play keeps its files. Same layout as the phone app's filesDir, so a copied `.sav`
 * works both ways:
 *
 *     roms/<sha1>.gba         the one current ROM
 *     saves/<sha1>.sav        in-game save
 *     states/<sha1>/...       snapshots (see AutoSave)
 *     settings.properties, rom.properties, log.txt, app.lock
 *
 * The folder is `Link Play` under the per-user application data folder (not the roaming one on
 * Windows: a ROM and its states would sync between computers). Tests and a second copy of the app
 * pass `--data <dir>` to use another folder: call [init] once, first thing in main.
 *
 * Windows paths: mGBA copies a path into a 260-character buffer, so [MAX_PATH_CHARS] is the longest
 * path we hand to the native side. The default folder plus a 40-character ROM name is about 110.
 */
object AppDirs {
    /** The folder name on every OS (it is also the jpackage app name). */
    const val FOLDER = "Link Play"

    /** Longest path (UTF-16 units) passed to the native side on Windows; mGBA's buffer is 260. */
    const val MAX_PATH_CHARS = 240

    private var dataOverride: File? = null
    private var rootDir: File? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null

    /** Picks the data folder: [dataDir] (from `--data`) or the OS default. Creates it. */
    @Synchronized
    fun init(dataDir: File? = null) {
        dataOverride = dataDir?.absoluteFile
        rootDir = null
        root.mkdirs()
    }

    /** The data folder. Created on first use. */
    val root: File
        @Synchronized get() {
            rootDir?.let { return it }
            val dir = dataOverride ?: defaultRoot().toFile()
            dir.mkdirs()
            rootDir = dir
            return dir
        }

    val romsDir: File get() = File(root, "roms").apply { mkdirs() }
    val savesDir: File get() = File(root, "saves").apply { mkdirs() }
    val statesRoot: File get() = File(root, "states")
    val settingsFile: File get() = File(root, "settings.properties")
    val romFile: File get() = File(root, "rom.properties")
    val logFile: File get() = File(root, "log.txt")
    val lockFile: File get() = File(root, "app.lock")

    /** One ROM's snapshot folder (not created). */
    fun statesDir(sha1: String): File = File(statesRoot, sha1)

    /** The OS default data folder (the same code runs on every OS, so it can be checked from any of them). */
    fun defaultRoot(
        osName: String = System.getProperty("os.name") ?: "",
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home") ?: ".",
    ): Path {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.contains("mac") -> Paths.get(home, "Library", "Application Support", FOLDER)
            os.contains("windows") -> {
                val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                if (local != null) Paths.get(local, FOLDER) else Paths.get(home, "AppData", "Local", FOLDER)
            }
            else -> {
                // Linux and others: not a supported target, but handy for development.
                val xdg = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                if (xdg != null) Paths.get(xdg, "link-play") else Paths.get(home, ".local", "share", "link-play")
            }
        }
    }

    /**
     * Claims the data folder for this process (one app per folder: two would map the same save file
     * and write the same snapshots). False if another Link Play already has it. The lock goes away
     * with the process, so a crash never leaves the app locked.
     */
    @Synchronized
    fun lockInstance(): Boolean {
        if (lock != null) return true
        return try {
            val channel = RandomAccessFile(lockFile, "rw").channel
            val l = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null // this JVM already holds it
            }
            if (l == null) {
                channel.close()
                false
            } else {
                lockChannel = channel
                lock = l
                true
            }
        } catch (e: Exception) {
            // Can't even create the lock file (read-only folder?): don't block the app over it.
            Log.w("app.lock: ${e.message}")
            true
        }
    }

    /** Lets go of the data folder (tests only; the lock normally lives until the process ends). */
    @Synchronized
    fun unlockInstance() {
        try { lock?.release() } catch (_: Exception) {}
        try { lockChannel?.close() } catch (_: Exception) {}
        lock = null
        lockChannel = null
    }
}

/**
 * Moves [from] over [to], replacing it. File.renameTo cannot replace an existing file on Windows,
 * which would make every second snapshot or settings save fail there, so everything that replaces
 * a file goes through here. Atomic when the file system can do it.
 */
internal fun moveReplacing(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
