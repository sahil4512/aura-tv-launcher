package com.auratv.launcher.backup

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.File

/**
 * Backup places on the TV itself: its own storage and every USB drive / SD card that is mounted.
 *
 * Android lets an app write anywhere on a USB drive or SD card only through its own folder there
 * (`<drive>/Android/data/<package>/files`); the drive's top level is read-only for apps on most
 * TVs. So a backup goes to `<drive>/AuraTV` when that can be written, else to GlowHome's folder on
 * the drive, and a restore looks in the drive's top level, `AuraTV` and GlowHome's folder.
 */
object LocalStorage {

    /** [dirs]: where to look for backups; [writeDirs]: where to try saving one, in order. */
    class Place(val label: String, val detail: String, val dirs: List<File>, val writeDirs: List<File>)

    /** This TV ([tvDir], the folder backups always used) first, then each mounted USB drive / SD card. */
    fun places(ctx: Context, tvDir: File): List<Place> {
        val out = ArrayList<Place>()
        out += Place("This TV", tvDir.path, listOf(tvDir), listOf(tvDir))
        val appDirs = try { ctx.getExternalFilesDirs(null) } catch (_: Exception) { emptyArray<File?>() }
        // Index 0 is the TV's own shared storage; the others are removable volumes (null: not mounted).
        for (appDir in appDirs.drop(1).filterNotNull()) {
            val path = appDir.path
            val i = path.indexOf("/Android/data/")
            if (i <= 0) continue
            val root = File(path.substring(0, i))
            val name = description(ctx, appDir) ?: "USB drive / SD card"
            out += Place(name, root.path,
                dirs = listOf(root, File(root, "AuraTV"), appDir),
                writeDirs = listOf(File(root, "AuraTV"), appDir))
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

    /** Backups in a place, newest first (each file once). */
    fun backups(p: Place): List<File> {
        val seen = HashSet<String>()
        val files = p.dirs.flatMap { d -> d.listFiles { f -> f.isFile && BackupNames.isBackup(f.name) }?.toList() ?: emptyList() }
            .filter { seen.add(try { it.canonicalPath } catch (_: Exception) { it.path }) }
        return BackupNames.newestFirst(files, { it.name }, { it.lastModified() })
    }

    /** Writes [text] as [name] into the first writable folder of the place; returns the file, or throws. */
    fun write(p: Place, name: String, text: String): File {
        var last: Exception? = null
        for (d in p.writeDirs) {
            try {
                d.mkdirs()
                val f = File(d, name)
                f.writeText(text)
                return f
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("nowhere to write")
    }
}
