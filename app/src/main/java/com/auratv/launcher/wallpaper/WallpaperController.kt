package com.auratv.launcher.wallpaper

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.util.Bg
import java.io.File

/**
 * Glue between preferences and [WallpaperView]: chooses image vs video, decodes off-thread,
 * schedules auto-change while visible (never while in the background) and handles memory trims.
 */
class WallpaperController(
    private val view: WallpaperView,
    private val prefs: Prefs,
    val catalog: WallpaperCatalog,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onFirstWallpaperShown()
        fun onVideoStruggling(dropPercent: Int)
        fun onVideoError(message: String)
    }

    private var currentId: String? = null
    private var loadingId: String? = null
    private var visible = false
    private var firstShown = false
    private var decodeToken = 0

    private val rotateRunnable = Runnable { advance(+1) }

    /** Home wallpapers are always still since 0.6.2: no Ken Burns pan, no parallax (the screensaver keeps its own pan). */
    fun applyMotionSettings() {
        view.cinematic = false
        view.fps60 = false
        view.parallaxEnabled = false
    }

    /** Solid or glow: Home draws its own backdrop and no wallpaper image is decoded at all. */
    private val noImage get() = prefs.wallpaperType == Prefs.TYPE_GLOW || prefs.wallpaperType == Prefs.TYPE_SOLID

    /** Shows whatever the preferences say should be on screen. */
    fun refresh(animate: Boolean = true) {
        applyMotionSettings()
        if (noImage) {
            // No image at all: drop any decoded wallpaper (and with it the motion loop).
            decodeToken++
            loadingId = null
            currentId = null
            view.onHidden() // stops Ken Burns and releases a video decoder
            view.releaseImages()
            Bg.main.removeCallbacks(rotateRunnable)
            if (!firstShown) { firstShown = true; callbacks.onFirstWallpaperShown() }
            return
        }
        if (visible) view.onShown() // coming back from glow while Home is visible
        if (prefs.wallpaperType == Prefs.TYPE_VIDEO && prefs.videoPath.isNotEmpty() && File(prefs.videoPath).canRead()) {
            currentId = null
            view.showVideo(prefs.videoPath, videoListener)
            if (!firstShown) { firstShown = true; callbacks.onFirstWallpaperShown() }
            Bg.main.removeCallbacks(rotateRunnable)
            return
        }
        showImage(prefs.wallpaperId, animate)
        scheduleRotation()
    }

    private val videoListener = object : VideoLoopPlayer.Listener {
        override fun onVideoSize(width: Int, height: Int) {
            Log.i(TAG, "video ${width}x$height")
        }
        override fun onStruggling(dropPercent: Int) = callbacks.onVideoStruggling(dropPercent)
        override fun onError(message: String) {
            // Fall back to images so the user never ends up with a black screen.
            prefs.wallpaperType = Prefs.TYPE_IMAGE
            refresh()
            callbacks.onVideoError(message)
        }
    }

    private fun showImage(id: String, animate: Boolean) {
        if (id == currentId && view.hasImage && !view.isVideo) return
        if (id == loadingId) return
        loadingId = id
        val token = ++decodeToken
        val (w, h) = DecodeSize.forScreen(
            view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels,
            view.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels)
        Bg.decode.execute {
            val t0 = SystemClock.uptimeMillis()
            var bmp: Bitmap? = catalog.decode(id, w, h)
            var shownId = id
            if (bmp == null) { // imported file deleted? fall back to the default art
                shownId = Prefs.DEFAULT_WALLPAPER
                bmp = catalog.decode(shownId, w, h)
            }
            Log.i(TAG, "decoded $shownId ${bmp?.width}x${bmp?.height} in ${SystemClock.uptimeMillis() - t0} ms")
            Bg.main.post {
                if (token != decodeToken) return@post
                loadingId = null
                if (bmp == null) return@post
                currentId = shownId
                val firstFrame = !firstShown
                view.setImage(bmp, animate = true, zoom = false,
                    durationMs = if (firstFrame) 450 else if (animate) 1600 else 600)
                if (firstFrame) { firstShown = true; callbacks.onFirstWallpaperShown() }
            }
        }
    }

    /** Next/previous wallpaper in the rotation (built-ins + imports). */
    fun advance(dir: Int) {
        val all = catalog.all(prefs.importedWallpapers)
        if (all.isEmpty()) return
        val idx = all.indexOfFirst { it.id == prefs.wallpaperId }
        val next = all[((if (idx < 0) 0 else idx) + dir + all.size) % all.size]
        prefs.wallpaperId = next.id
        prefs.lastWallpaperChange = System.currentTimeMillis()
        if (prefs.wallpaperType == Prefs.TYPE_IMAGE) showImage(next.id, true)
        scheduleRotation()
    }

    fun select(id: String) {
        prefs.wallpaperId = id
        prefs.wallpaperType = Prefs.TYPE_IMAGE
        prefs.lastWallpaperChange = System.currentTimeMillis()
        refresh()
    }

    private fun scheduleRotation() {
        Bg.main.removeCallbacks(rotateRunnable)
        val minutes = prefs.rotationMinutes
        if (!visible || minutes <= 0 || prefs.wallpaperType != Prefs.TYPE_IMAGE) return
        val interval = minutes * 60_000L
        val last = prefs.lastWallpaperChange.takeIf { it > 0 } ?: System.currentTimeMillis().also {
            prefs.lastWallpaperChange = it
        }
        val due = (last + interval - System.currentTimeMillis()).coerceAtLeast(0L)
        // When coming back after a long time the change happens right away, as a gentle crossfade.
        Bg.main.postDelayed(rotateRunnable, if (due == 0L) 1500 else due)
    }

    fun onStart() {
        visible = true
        if (noImage) return
        view.onShown()
        if (prefs.wallpaperType == Prefs.TYPE_IMAGE && !view.hasImage) {
            currentId = null
            showImage(prefs.wallpaperId, true)
        }
        scheduleRotation()
    }

    val isGlow get() = prefs.wallpaperType == Prefs.TYPE_GLOW
    val usesBackdrop get() = noImage

    fun onStop() {
        visible = false
        view.onHidden()
        Bg.main.removeCallbacks(rotateRunnable)
    }

    /** Called on memory pressure while hidden: drop the decoded wallpaper (re-decoded on return). */
    fun releaseMemory() {
        if (visible) return
        decodeToken++
        loadingId = null
        currentId = null
        view.releaseImages()
    }

    companion object {
        private const val TAG = "AuraWallpaper"
    }
}
