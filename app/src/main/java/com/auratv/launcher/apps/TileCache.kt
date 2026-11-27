package com.auratv.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.Log
import android.util.LruCache
import com.auratv.launcher.R
import com.auratv.launcher.util.Bg
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a 16:9 tile bitmap for every app — the app's TV banner when it has one, otherwise a
 * generated tile (icon on a gradient sampled from the icon's own colours).
 *
 * Three cache levels: memory LRU → disk PNG (survives process death, ~3 ms to decode) → render
 * from PackageManager (slow: loads another app's resources). Rendering happens off the UI thread.
 */
class TileCache(private val ctx: Context) {

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    private fun key(e: AppEntry, w: Int, h: Int) = "${e.key}@${e.updatedAt}@${w}x$h@v$STYLE"

    /** Returns immediately on a memory hit, otherwise calls [cb] on the main thread later. */
    fun get(e: AppEntry, w: Int, h: Int, cb: (Bitmap) -> Unit): Bitmap? {
        val k = key(e, w, h)
        memory.get(k)?.let { return it }
        val waiting = pending[k]
        if (waiting != null) {
            waiting.add(cb)
            return null
        }
        pending[k] = mutableListOf(cb)
        Bg.apps.execute {
            val bmp = try { loadOrRender(e, w, h) } catch (t: Throwable) {
                Log.w(TAG, "tile failed for ${e.key}", t); null
            }
            Bg.main.post {
                val cbs = pending.remove(k) ?: return@post
                if (bmp != null) {
                    memory.put(k, bmp)
                    cbs.forEach { it(bmp) }
                }
            }
        }
        return null
    }

    fun invalidatePackage(pkg: String) {
        memory.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { memory.remove(it) }
        Bg.apps.execute { dir.listFiles()?.filter { it.name.startsWith("$pkg~") }?.forEach { it.delete() } }
    }

    /** After an app scan: drop disk tiles of uninstalled apps and old generations, then cap the total size. */
    fun prune(apps: List<AppEntry>) {
        val live = apps.associate { it.packageName to TileFiles.generation(it.updatedAt, STYLE) }
        Bg.apps.execute {
            val files = dir.listFiles() ?: return@execute
            val doomed = TileFiles.prunable(files.map { it.name }, live).toHashSet()
            val kept = files.filter { it.name !in doomed }
            doomed += TileFiles.overBudget(kept.map { TileFiles.Info(it.name, it.length(), it.lastModified()) }, DISK_BYTES)
            var n = 0
            for (f in files) if (f.name in doomed && f.delete()) n++
            if (n > 0) Log.i(TAG, "pruned $n tile files")
        }
    }

    fun trimMemory() = memory.evictAll()

    fun clearAll() {
        memory.evictAll()
        Bg.apps.execute { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun loadOrRender(e: AppEntry, w: Int, h: Int): Bitmap? {
        val f = File(dir, TileFiles.name(e, w, h, STYLE))
        if (f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val bmp = render(e, w, h) ?: return null
        try {
            // Remove stale renders of this package (older versions / styles). Other sizes stay:
            // favourites and recents show the same app at different sizes.
            val gen = TileFiles.generation(e.updatedAt, STYLE)
            dir.listFiles()?.filter { TileFiles.isStale(it.name, e.packageName, gen) }?.forEach { it.delete() }
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {}
        return bmp
    }

    private fun render(e: AppEntry, w: Int, h: Int): Bitmap? {
        val pm = ctx.packageManager
        val ai = try {
            pm.getActivityInfo(ComponentName(e.packageName, e.className), 0)
        } catch (ex: PackageManager.NameNotFoundException) {
            return null
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val systemIcon = when (e.packageName) {
            "com.tcl.sourcemananger" -> R.drawable.ic_tv_inputs
            "com.android.tv.settings", "com.tcl.settings", "com.android.settings",
            "com.amazon.tv.settings", "com.amazon.tv.settings.v2" -> R.drawable.ic_system
            else -> if (e.system && e.label.equals("TV Settings", true)) R.drawable.ic_system else null
        }
        if (systemIcon != null) {
            val inputs = systemIcon == R.drawable.ic_tv_inputs
            c.drawColor(if (inputs) 0xff205961.toInt() else 0xff435a75.toInt())
            val size = (h * .55f).toInt()
            ctx.getDrawable(systemIcon)!!.apply {
                setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
                draw(c)
            }
            return out
        }
        val banner: Drawable? = try { ai.loadBanner(pm) } catch (_: Exception) { null }
        val artwork = banner?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
            ?: loadBestIcon(pm, ai) ?: pm.defaultActivityIcon
        // Sample the artwork's border before drawing. A square app icon with a solid
        // background can continue seamlessly into the card, just like a TV banner.
        val sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val old = Rect(artwork.bounds)
        artwork.setBounds(0, 0, 24, 24)
        artwork.draw(Canvas(sample))
        val edge = sample.getPixel(12, 0)
        fun matchesEdge(x: Int, y: Int): Boolean {
            val pixel = sample.getPixel(x, y)
            return Color.alpha(pixel) >= 245 &&
                kotlin.math.abs(Color.red(pixel) - Color.red(edge)) <= 12 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(edge)) <= 12 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(edge)) <= 12
        }
        val solid = (0 until 24).all { matchesEdge(it, 0) && matchesEdge(it, 23) &&
            matchesEdge(0, it) && matchesEdge(23, it) }
        if (solid) {
            c.drawColor(edge)
        } else {
            val hsv = FloatArray(3)
            Color.colorToHSV(averageColor(sample), hsv)
            hsv[1] = (hsv[1] * 0.9f).coerceAtMost(0.7f)
            hsv[2] = TileTone.ICON_CARD_VALUE
            val tint = Color.HSVToColor(hsv)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    blend(Color.rgb(30, 34, 46), tint, 0.55f),
                    blend(Color.rgb(14, 16, 24), tint, 0.25f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }

        // Keep the entire supplied artwork, including any gradient behind its wordmark.
        // A wide launcher icon is useful banner art even when the app declares no banner.
        val (dw, dh) = TileTone.artworkSize(artwork.intrinsicWidth, artwork.intrinsicHeight, w, h, solid)
        val left = (w - dw) / 2
        val top = (h - dh) / 2
        // Drawer cards are taller than Home banners. Continue only the outside edge
        // into that space, so the artist's gradient meets the logo without a seam.
        if (!solid && TileTone.isWideArtwork(artwork.intrinsicWidth, artwork.intrinsicHeight)) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val last = sample.width - 1
            if (dh < h) {
                c.drawBitmap(sample, Rect(0, 0, last + 1, 1), Rect(0, 0, w, top + 1), paint)
                c.drawBitmap(sample, Rect(0, last, last + 1, last + 1), Rect(0, top + dh - 1, w, h), paint)
            } else if (dw < w) {
                c.drawBitmap(sample, Rect(0, 0, 1, last + 1), Rect(0, 0, left + 1, h), paint)
                c.drawBitmap(sample, Rect(last, 0, last + 1, last + 1), Rect(left + dw - 1, 0, w, h), paint)
            }
        }
        sample.recycle()
        if (artwork is BitmapDrawable) artwork.setFilterBitmap(true)
        artwork.setBounds(left, top, left + dw, top + dh)
        artwork.draw(c)
        artwork.bounds = old
        if (solid) {
            val shade = TileTone.shadeAlpha(TileTone.luma(Color.red(edge), Color.green(edge), Color.blue(edge)))
            if (shade > 0) c.drawColor(Color.argb(shade, 0, 0, 0))
        }
        return out
    }

    private fun loadBestIcon(pm: PackageManager, ai: android.content.pm.ActivityInfo): Drawable? {
        // Ask the app's resources for its highest-density icon so it stays crisp at TV tile size.
        val res = ai.iconResource.takeIf { it != 0 } ?: ai.applicationInfo.icon
        if (res != 0) {
            try {
                val r = pm.getResourcesForApplication(ai.applicationInfo)
                @Suppress("DEPRECATION")
                return r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH)
            } catch (_: Exception) {}
        }
        return try { ai.loadIcon(pm) } catch (_: Exception) { null }
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** Saturation-weighted average colour of a drawable (rendered at 24×24). */
    private fun averageColor(b: Bitmap): Int {
        var r = 0.0; var g = 0.0; var bl = 0.0; var wsum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val px = b.getPixel(x, y)
            val a = Color.alpha(px)
            if (a < 128) continue
            Color.colorToHSV(px, hsv)
            val wt = 0.15 + hsv[1] * hsv[2]
            r += Color.red(px) * wt; g += Color.green(px) * wt; bl += Color.blue(px) * wt; wsum += wt
        }
        if (wsum == 0.0) return Color.rgb(40, 48, 70)
        return Color.rgb(
            (r / wsum).toInt().coerceIn(0, 255),
            (g / wsum).toInt().coerceIn(0, 255),
            (bl / wsum).toInt().coerceIn(0, 255),
        )
    }

    companion object {
        private const val TAG = "AuraTiles"
        private const val MEMORY_BYTES = 14 * 1024 * 1024
        /** Hard cap for the disk cache (about 3–4x a typical TV's tiles at two sizes). */
        private const val DISK_BYTES = 24L * 1024 * 1024
        /** Bump when the tile artwork style changes so disk caches re-render. */
        private const val STYLE = 7 // Keep native artwork proportions and extend solid icon backgrounds.
    }
}
