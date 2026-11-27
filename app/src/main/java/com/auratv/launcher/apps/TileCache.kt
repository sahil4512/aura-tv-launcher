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
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
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
        if (banner != null && banner.intrinsicWidth > 0 && banner.intrinsicHeight > 0) {
            drawBanner(c, banner, w, h)
            return out
        }
        val icon = loadBestIcon(pm, ai) ?: pm.defaultActivityIcon
        drawIconTile(c, icon, e.label, w, h)
        return out
    }

    /**
     * Card style from the Aura design: the app's logo, large and centred, on the banner's own
     * background colour. The logo's bounding box is found by comparing pixels to the banner's edge
     * colour, so wide wordmarks are never clipped and small logos aren't lost in empty space.
     */
    private fun drawBanner(c: Canvas, d: Drawable, w: Int, h: Int) {
        val bw = d.intrinsicWidth.coerceIn(16, 640)
        val bh = d.intrinsicHeight.coerceIn(9, 360)
        val src = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bw, bh)
        d.draw(Canvas(src))
        val px = IntArray(bw * bh)
        src.getPixels(px, 0, bw, 0, 0, bw, bh)
        val edge = edgeColor(px, bw, bh)
        // Bounding box of everything that differs clearly from the edge colour.
        var x0 = bw; var y0 = bh; var x1 = -1; var y1 = -1
        val er = Color.red(edge); val eg = Color.green(edge); val eb = Color.blue(edge)
        for (y in 0 until bh) for (x in 0 until bw) {
            val p = px[y * bw + x]
            if (Color.alpha(p) < 40) continue
            val diff = kotlin.math.abs(Color.red(p) - er) + kotlin.math.abs(Color.green(p) - eg) + kotlin.math.abs(Color.blue(p) - eb)
            if (diff > 70) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val full = x1 < 0 || ((x1 - x0) > bw * 0.92f && (y1 - y0) > bh * 0.88f)
        if (full) {
            // Edge-to-edge artwork (gradients, photos): fit the full width so nothing is cut off, and
            // extend the banner's own top and bottom pixel rows to fill the taller card.
            val scale = w.toFloat() / bw
            val dh = bh * scale
            if (dh >= h) {
                c.drawBitmap(src, null, android.graphics.RectF(0f, (h - dh) / 2, w.toFloat(), (h + dh) / 2), paint)
            } else {
                val top = (h - dh) / 2
                c.drawBitmap(src, Rect(0, 0, bw, 2), android.graphics.RectF(0f, 0f, w.toFloat(), top + 1), paint)
                c.drawBitmap(src, Rect(0, bh - 2, bw, bh), android.graphics.RectF(0f, top + dh - 1, w.toFloat(), h.toFloat()), paint)
                c.drawBitmap(src, null, android.graphics.RectF(0f, top, w.toFloat(), top + dh), paint)
            }
        } else {
            c.drawColor(edge or 0xFF000000.toInt())
            val cw = (x1 - x0 + 1).toFloat(); val ch = (y1 - y0 + 1).toFloat()
            val scale = minOf(w * 0.80f / cw, h * 0.56f / ch, 3f)
            val dw = cw * scale; val dh = ch * scale
            c.drawBitmap(src, Rect(x0, y0, x1 + 1, y1 + 1),
                android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
        }
        src.recycle()
        val edgeLuma = TileTone.luma(Color.red(edge), Color.green(edge), Color.blue(edge))
        val shade = TileTone.shadeAlpha(edgeLuma)
        if (shade > 0) c.drawColor(Color.argb(shade, 0, 0, 0)) // near-white banners: uniform, logo-safe shade
        if (TileTone.wantsSheen(edgeLuma)) {
            // Gentle top sheen so flat brand colours read as a lit glass card.
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(34, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h * 0.5f, paint)
        }
    }

    private fun edgeColor(px: IntArray, w: Int, h: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        fun add(p: Int) { if (Color.alpha(p) > 200) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++ } }
        for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        if (n == 0L) return Color.rgb(24, 28, 40)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
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

    /** No TV banner: the icon on a dark glass card tinted with the icon's own colour. */
    @Suppress("UNUSED_PARAMETER")
    private fun drawIconTile(c: Canvas, icon: Drawable, label: String, w: Int, h: Int) {
        val base = averageColor(icon, darken = 1f)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[1] = (hsv[1] * 0.9f).coerceAtMost(0.7f)
        hsv[2] = TileTone.ICON_CARD_VALUE
        val tint = Color.HSVToColor(hsv)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            blend(Color.rgb(30, 34, 46), tint, 0.55f), blend(Color.rgb(14, 16, 24), tint, 0.25f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.5f, h * 0.0f, w * 0.75f,
            Color.argb(46, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val size = (h * 0.50f).toInt()
        val cx = w / 2
        val cy = (h * 0.47f).toInt()
        icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        icon.draw(c)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** Saturation-weighted average colour of a drawable (rendered at 24×24). */
    private fun averageColor(d: Drawable, darken: Float): Int {
        val s = 24
        val b = if (d is BitmapDrawable && d.bitmap != null) {
            Bitmap.createScaledBitmap(d.bitmap, s, s, true)
        } else {
            val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val old = Rect(d.bounds)
            d.setBounds(0, 0, s, s)
            d.draw(Canvas(bm))
            d.bounds = old
            bm
        }
        var r = 0.0; var g = 0.0; var bl = 0.0; var wsum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until s) for (x in 0 until s) {
            val px = b.getPixel(x, y)
            val a = Color.alpha(px)
            if (a < 128) continue
            Color.colorToHSV(px, hsv)
            val wt = 0.15 + hsv[1] * hsv[2]
            r += Color.red(px) * wt; g += Color.green(px) * wt; bl += Color.blue(px) * wt; wsum += wt
        }
        if (wsum == 0.0) return Color.rgb(40, 48, 70)
        return Color.rgb(
            (r / wsum * darken).toInt().coerceIn(0, 255),
            (g / wsum * darken).toInt().coerceIn(0, 255),
            (bl / wsum * darken).toInt().coerceIn(0, 255),
        )
    }

    companion object {
        private const val TAG = "AuraTiles"
        private const val MEMORY_BYTES = 14 * 1024 * 1024
        /** Hard cap for the disk cache (about 3–4x a typical TV's tiles at two sizes). */
        private const val DISK_BYTES = 24L * 1024 * 1024
        /** Bump when the tile artwork style changes so disk caches re-render. */
        private const val STYLE = 6 // Includes built-in TV Settings and TV Inputs artwork.
    }
}
