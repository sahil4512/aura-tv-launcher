package com.auratv.launcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Wallpaper sources. Ids are "asset:<name>" for bundled art and "file:<path>" for imports.
 * Imports are re-encoded once at screen resolution so we never decode a 20 MP photo at runtime.
 */
class WallpaperCatalog(private val ctx: Context) {

    data class Item(val id: String, val name: String)

    val builtIns = listOf(
        Item("asset:lake.jpg", "Mountain Lake"),
        Item("asset:aurora.jpg", "Aurora"),
        Item("asset:alpine.jpg", "Alpine Dawn"),
        Item("asset:dunes.jpg", "Dunes"),
        Item("asset:ocean.jpg", "Ocean Hour"),
        Item("asset:nebula.jpg", "Nebula"),
        Item("asset:forest.jpg", "Mist Forest"),
        Item("asset:silk.jpg", "Silk"),
    )

    private val importDir = File(ctx.filesDir, "wallpapers").apply { mkdirs() }

    fun all(imported: List<String>): List<Item> =
        builtIns + imported.filter { File(it.removePrefix("file:")).exists() }
            .mapIndexed { i, id -> Item(id, "Imported ${i + 1}") }

    fun open(id: String): InputStream? = try {
        when {
            id.startsWith("asset:") -> ctx.assets.open("wallpapers/" + id.removePrefix("asset:"))
            id.startsWith("file:") -> File(id.removePrefix("file:")).inputStream()
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Decode at (approximately) the screen's resolution: subsample with inSampleSize, then a final
     * exact scale to "cover" the target. Peak memory stays near one screen-sized bitmap.
     */
    fun decode(id: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open(id) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) } // bounds only: returns null by design
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = open(id)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return coverScale(raw, targetW, targetH)
    }

    /** Small thumbnail for the picker. */
    fun thumbnail(id: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open(id) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) } // bounds only: returns null by design
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
        }
        val raw = open(id)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return coverScale(raw, w, h)
    }

    /** Copies a user image into private storage, downscaled and EXIF-rotated. Returns the new id. */
    fun import(src: File, screenW: Int, screenH: Int): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, screenW, screenH)
        }
        var bmp = BitmapFactory.decodeFile(src.path, opts) ?: return null
        val rotation = try {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        bmp = coverScale(bmp, screenW, screenH)
        val out = File(importDir, "img_${(src.path + src.lastModified()).hashCode().toUInt().toString(16)}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return "file:" + out.path
    }

    fun deleteImported(id: String) {
        val f = File(id.removePrefix("file:"))
        if (f.parentFile == importDir) f.delete()
    }

    /** Images and videos the user put on the device (via Downloader, ADB, USB…). */
    fun scanMedia(video: Boolean): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val dirs = listOf("AuraTV", "Download", "Downloads", "Pictures", "Movies", "DCIM", "Wallpapers")
            .map { File(root, it) }
        val ext = if (video) VIDEO_EXT else IMAGE_EXT
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (depth < 2 && !f.name.startsWith(".")) walk(f, depth + 1)
                } else if (f.extension.lowercase() in ext && f.length() > 0) {
                    out.add(f)
                }
                if (out.size >= 200) return
            }
        }
        dirs.forEach { walk(it, 0) }
        return out.sortedByDescending { it.lastModified() }
    }

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
        val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "3gp")

        fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
            var s = 1
            while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
            return s
        }

        /** Scales (and centre-crops) to exactly targetW×targetH. */
        fun coverScale(src: Bitmap, tw: Int, th: Int): Bitmap {
            if (src.width == tw && src.height == th) return src
            val scale = maxOf(tw.toFloat() / src.width, th.toFloat() / src.height)
            val cropW = (tw / scale).toInt().coerceAtMost(src.width)
            val cropH = (th / scale).toInt().coerceAtMost(src.height)
            val x = (src.width - cropW) / 2
            val y = (src.height - cropH) / 2
            val m = Matrix().apply { setScale(tw.toFloat() / cropW, th.toFloat() / cropH) }
            val out = Bitmap.createBitmap(src, x, y, cropW, cropH, m, true)
            if (out != src) src.recycle()
            return out
        }
    }
}
