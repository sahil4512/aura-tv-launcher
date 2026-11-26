package com.auratv.launcher.backup

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Storage on the TV for backups: the internal shared storage and every USB drive / SD card that
 * is mounted, browsed folder by folder like a network share.
 *
 * Android does not let apps write everywhere: on USB drives / SD cards only inside the app's own
 * folder (`Android/data/<package>/files`), and from Android 10 on internal storage outside a few
 * folders neither. When a folder can't be written, [Volume.appDir] is the place that always works.
 */
object LocalStorage {

    /** [root]: top of the volume; [appDir]: GlowHome's own folder on it (always writable), if any. */
    class Volume(val label: String, val root: File, val appDir: File?, val removable: Boolean)

    /** A folder's contents: sub-folders A–Z and backups newest first, or null if it can't be read. */
    class Listing(val dirs: List<File>, val backups: List<File>)

    /** Internal storage first, then each mounted USB drive / SD card. */
    fun volumes(ctx: Context): List<Volume> {
        val out = ArrayList<Volume>()
        val appDirs = try { ctx.getExternalFilesDirs(null) } catch (_: Exception) { emptyArray<File?>() }
        @Suppress("DEPRECATION")
        out += Volume("Internal Storage", Environment.getExternalStorageDirectory(), appDirs.getOrNull(0), false)
        // Index 0 is the internal shared storage; the others are removable volumes (null: not mounted).
        for (appDir in appDirs.drop(1).filterNotNull()) {
            val path = appDir.path
            val i = path.indexOf("/Android/data/")
            if (i <= 0) continue
            out += Volume(description(ctx, appDir) ?: "USB drive / SD card", File(path.substring(0, i)), appDir, true)
        }
        return out
    }

    /** "SanDisk USB drive", "SD card"… as the TV names the volume (Android 7+). */
    private fun description(ctx: Context, f: File): String? {
        if (Build.VERSION.SDK_INT < 24) return null
        return try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.getStorageVolume(f)?.getDescription(ctx)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Sub-folders (A–Z) and backups (newest first) of [dir]; hidden entries left out. */
    fun list(dir: File): Listing? {
        val all = dir.listFiles() ?: return null
        val dirs = all.filter { it.isDirectory && !it.name.startsWith(".") }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val files = all.filter { it.isFile && BackupNames.isBackup(it.name) }
        return Listing(dirs, BackupNames.newestFirst(files, { it.name }, { it.lastModified() }))
    }

    /** Writes [text] as [name] into [dir] (created if missing); returns the file, or throws. */
    fun write(dir: File, name: String, text: String): File {
        dir.mkdirs()
        val f = File(dir, name)
        f.writeText(text)
        return f
    }
}
