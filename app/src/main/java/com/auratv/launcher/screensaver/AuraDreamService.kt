package com.auratv.launcher.screensaver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.auratv.launcher.AuraApp
import com.auratv.launcher.ui.Style
import com.auratv.launcher.util.Bg
import com.auratv.launcher.util.Dp
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp
import com.auratv.launcher.wallpaper.DecodeSize
import com.auratv.launcher.wallpaper.WallpaperCatalog
import com.auratv.launcher.wallpaper.WallpaperView

/**
 * Aura's screensaver: the photo of the day (Bing or NASA, cached by [ScreensaverPhotos]) with a slow
 * Ken Burns pan, a new photo every [ROTATE_MS], and the clock and date in Aura's style. Falls back
 * to Aura's built-in wallpapers when nothing is cached (offline, first run).
 *
 * Android starts it after the idle time set in Settings → Device Preferences → Screen saver; video
 * apps keep the screen awake, so it never interrupts playback.
 */
class AuraDreamService : DreamService() {

    private lateinit var wall: WallpaperView
    private lateinit var title: TextView
    private lateinit var credit: TextView
    private val catalog by lazy { WallpaperCatalog(this) }
    private var items: List<Pair<String, ScreensaverPhotos.Cached?>> = emptyList()
    private var index = 0
    private var token = 0
    private val next = Runnable { showNext(animate = true) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        Dp.init(this)
        val prefs = (application as AuraApp).prefs
        Style.load(prefs)

        wall = WallpaperView(this).apply {
            cinematic = true
            intensity = 1
            speed = 0.8f
            parallaxEnabled = false
        }
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(190, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.TRANSPARENT))
        }
        val is24 = when (prefs.clockFormat) { 1 -> false; 2 -> true; else -> DateFormat.is24HourFormat(this) }
        val clock = TextClock(this).apply {
            sizeDp(64f); typeface = Style.LIGHT; includeFontPadding = false; setTextColor(Color.WHITE)
            val f = if (is24) "HH:mm" else "h:mm"
            format12Hour = f; format24Hour = f
        }
        val date = TextClock(this).apply {
            sizeDp(13f); typeface = Style.MEDIUM; letterSpacing = 0.2f; isAllCaps = true; setTextColor(Color.argb(220, 255, 255, 255))
            format12Hour = "EEEE  d MMMM"; format24Hour = "EEEE  d MMMM"
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp })
        }
        title = TextView(this).apply {
            sizeDp(14f); typeface = Style.MEDIUM; setTextColor(Color.WHITE); gravity = Gravity.END
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        credit = TextView(this).apply {
            sizeDp(11f); typeface = Style.REGULAR; setTextColor(Color.argb(190, 255, 255, 255)); gravity = Gravity.END
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(title, LinearLayout.LayoutParams(-2, -2))
            addView(credit, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.dp })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(wall, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, 220.dp, Gravity.BOTTOM))
            // Clock on the left, the photo's title and credit on the right taking the remaining width.
            addView(LinearLayout(this@AuraDreamService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(left, LinearLayout.LayoutParams(-2, -2))
                addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 48.dp; bottomMargin = 4.dp })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = 56.dp; rightMargin = 56.dp; bottomMargin = 44.dp })
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val prefs = (application as AuraApp).prefs
        load()
        wall.onShown()
        showNext(animate = false)
        // Top up the cache in the background; new photos join the rotation on the next change.
        ScreensaverPhotos.refreshIfStale(this, prefs) { if (isDreaming()) load() }
    }

    private fun isDreaming() = ::wall.isInitialized && wall.isAttachedToWindow

    private fun load() {
        val photos = ScreensaverPhotos.cached(this)
        val prefs = (application as AuraApp).prefs
        items = if (photos.isNotEmpty() && prefs.screensaverSource != ScreensaverPhotos.SOURCE_AURA) {
            photos.map { ("file:" + it.file.path) to it }
        } else {
            catalog.all(prefs.importedWallpapers).map { it.id to null }.shuffled()
        }
        if (index >= items.size) index = 0
    }

    private fun showNext(animate: Boolean) {
        Bg.main.removeCallbacks(next)
        val item = items.getOrNull(index) ?: return
        index = (index + 1) % items.size.coerceAtLeast(1)
        val t = ++token
        val (w, h) = DecodeSize.forScreen(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        Bg.decode.execute {
            val bmp: Bitmap? = catalog.decode(item.first, w, h)
            Bg.main.post {
                if (t != token || !isDreaming()) return@post
                if (bmp != null) {
                    wall.setImage(bmp, animate = true, zoom = animate, durationMs = if (animate) 1800 else 600)
                    title.text = item.second?.title ?: ""
                    credit.text = item.second?.credit?.let { if (it.isNotEmpty()) "© $it" else "" } ?: ""
                }
                Bg.main.postDelayed(next, ROTATE_MS)
            }
        }
    }

    override fun onDreamingStopped() {
        Bg.main.removeCallbacks(next)
        token++
        if (::wall.isInitialized) { wall.onHidden(); wall.releaseImages() }
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        Bg.main.removeCallbacks(next)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ROTATE_MS = 60_000L
    }
}
