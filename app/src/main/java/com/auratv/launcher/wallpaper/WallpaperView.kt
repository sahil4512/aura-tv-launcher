package com.auratv.launcher.wallpaper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Full-screen animated background.
 *
 * Structure (each level is a separate RenderNode, so motion only changes node *properties* —
 * no bitmap is re-decoded, re-uploaded or re-recorded per frame):
 *
 *   WallpaperView
 *   ├── video: SurfaceView (video mode only; hardware overlay, punches through the window)
 *   └── parallaxFrame   ← translationX on focus moves
 *       └── motionFrame ← Ken Burns scale/translation, updated once per (or every other) vsync
 *           ├── layerA  ← two layers for crossfades
 *           └── layerB
 */
class WallpaperView(context: Context) : FrameLayout(context) {

    // The zoomed motion layer is larger than its parent on purpose; the parallax frame must not clip
    // it, or shifting the parallax frame would expose an unpainted strip at the screen edge.
    private val parallaxFrame = FrameLayout(context).apply { clipChildren = false }
    private val motionFrame = FrameLayout(context)
    private var front = ImageLayer(context)
    private var back = ImageLayer(context)

    var cinematic = true
        set(v) { field = v; updateMotionRunning() }
    var intensity = 1
    var speed = 1.0f
    var fps60 = false
    var parallaxEnabled = true
        set(v) {
            field = v
            if (!v) parallaxFrame.animate().translationX(0f).setDuration(600).start()
        }

    private var visibleToUser = false
    private var motionTimeNs = (Math.random() * 600e9).toLong() // random start phase
    private var lastFrameNs = 0L
    private var frameCount = 0
    private val frame = KenBurns.Frame()

    var hasImage = false
        private set

    // Video
    private var surfaceView: SurfaceView? = null
    private var player: VideoLoopPlayer? = null
    private var videoPath: String? = null
    private var videoListener: VideoLoopPlayer.Listener? = null
    private var surfaceReady = false

    init {
        clipChildren = false // the window bounds do the clipping
        addView(parallaxFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        parallaxFrame.addView(motionFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        motionFrame.addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        motionFrame.addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        back.alpha = 0f
        front.alpha = 0f
    }

    val parallaxReserve: Float get() = width * 0.012f

    // ---------------- images ----------------

    /** Shows [bmp], crossfading from whatever is on screen. */
    fun setImage(bmp: Bitmap, animate: Boolean, zoom: Boolean, durationMs: Long = 1600) {
        hideVideo()
        val incoming = back
        val outgoing = front
        back = outgoing
        front = incoming
        outgoing.animate().cancel()
        incoming.bitmap = bmp
        incoming.animate().cancel()
        incoming.bringToFront()
        if (!animate) {
            incoming.alpha = 1f
            incoming.scaleX = 1f; incoming.scaleY = 1f
            outgoing.bitmap = null
            outgoing.alpha = 0f
        } else {
            incoming.alpha = 0f
            val z = if (zoom) 1.06f else 1f
            incoming.scaleX = z; incoming.scaleY = z
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(durationMs)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        incoming.animate().setListener(null)
                        // Release the previous wallpaper's pixels as soon as it is invisible.
                        if (outgoing !== front) {
                            outgoing.bitmap = null
                            outgoing.alpha = 0f
                        }
                    }
                }).start()
        }
        hasImage = true
        applyStaticFrame()
        updateMotionRunning()
    }

    /** Drops bitmaps (e.g. on memory pressure while in the background). */
    fun releaseImages() {
        front.animate().cancel(); back.animate().cancel()
        front.bitmap = null; back.bitmap = null
        front.alpha = 0f; back.alpha = 0f
        hasImage = false
    }

    // ---------------- video ----------------

    fun showVideo(path: String, listener: VideoLoopPlayer.Listener) {
        videoPath = path
        videoListener = listener
        stopMotion()
        parallaxFrame.visibility = View.GONE
        var sv = surfaceView
        if (sv == null) {
            sv = SurfaceView(context)
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    maybeStartVideo()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    stopVideoPlayer()
                }
            })
            surfaceView = sv
            addView(sv, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            stopVideoPlayer()
            maybeStartVideo()
        }
        // Image layers are no longer visible: free them.
        releaseImages()
    }

    fun hideVideo() {
        if (videoPath == null && surfaceView == null) return
        videoPath = null
        stopVideoPlayer()
        surfaceView?.let { removeView(it) }
        surfaceView = null
        surfaceReady = false
        parallaxFrame.visibility = View.VISIBLE
    }

    val isVideo: Boolean get() = videoPath != null

    private fun maybeStartVideo() {
        val p = videoPath ?: return
        val sv = surfaceView ?: return
        if (!surfaceReady || !visibleToUser || player != null) return
        val wrapped = object : VideoLoopPlayer.Listener {
            override fun onVideoSize(width: Int, height: Int) {
                coverVideo(sv, width, height)
                videoListener?.onVideoSize(width, height)
            }
            override fun onStruggling(dropPercent: Int) { videoListener?.onStruggling(dropPercent) }
            override fun onError(message: String) { videoListener?.onError(message) }
        }
        player = VideoLoopPlayer(p, sv.holder.surface, wrapped).also { it.start() }
    }

    private fun stopVideoPlayer() {
        player?.release()
        player = null
    }

    /** Centre-crop the video by oversizing the SurfaceView (the window clips the excess). */
    private fun coverVideo(sv: SurfaceView, vw: Int, vh: Int) {
        if (width == 0 || height == 0 || vw <= 0 || vh <= 0) return
        val scale = max(width.toFloat() / vw, height.toFloat() / vh)
        val lp = sv.layoutParams as LayoutParams
        lp.width = (vw * scale).toInt()
        lp.height = (vh * scale).toInt()
        lp.leftMargin = (width - lp.width) / 2
        lp.topMargin = (height - lp.height) / 2
        sv.layoutParams = lp
    }

    // ---------------- visibility / lifecycle ----------------

    fun onShown() {
        visibleToUser = true
        updateMotionRunning()
        maybeStartVideo()
    }

    fun onHidden() {
        visibleToUser = false
        stopMotion()
        stopVideoPlayer() // release the decoder entirely while another app is in front
    }

    /** Temporarily freeze motion (e.g. while a full-screen overlay covers the wallpaper). */
    var frozen = false
        set(v) { field = v; updateMotionRunning() }

    /** Paused because nobody has used the remote for a while (see MainActivity.noteUserActivity). */
    var idlePaused = false
        set(v) { if (field != v) { field = v; updateMotionRunning() } }

    // ---------------- parallax ----------------

    /** [fraction] in -1..1: where focus sits in the row. */
    fun parallaxTo(fraction: Float, durationMs: Long) {
        if (!parallaxEnabled || isVideo) return
        val target = -fraction.coerceIn(-1f, 1f) * parallaxReserve
        parallaxFrame.animate().translationX(target).setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
    }

    // ---------------- Ken Burns driver ----------------

    private var motionRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!motionRunning) return
            if (lastFrameNs != 0L) {
                val dt = (frameTimeNanos - lastFrameNs).coerceIn(0L, 100_000_000L) // clamp stalls
                motionTimeNs += (dt * speed).toLong()
            }
            lastFrameNs = frameTimeNanos
            // Balanced mode: update every other vsync (30 fps). Motion is slow (<0.4 px/frame),
            // so this is visually identical but halves GPU composition work.
            if (fps60 || (frameCount++ and 1) == 0) applyFrame()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun applyFrame() {
        if (width == 0) return
        KenBurns.compute(motionTimeNs / 1e9, intensity, width, height, parallaxReserve, frame)
        motionFrame.scaleX = frame.scale
        motionFrame.scaleY = frame.scale
        motionFrame.translationX = frame.tx
        motionFrame.translationY = frame.ty
    }

    private fun applyStaticFrame() {
        if (cinematic) return
        val s = KenBurns.baseScale(width, parallaxReserve)
        motionFrame.animate().scaleX(s).scaleY(s).translationX(0f).translationY(0f).setDuration(800).start()
    }

    private fun updateMotionRunning() {
        val should = cinematic && visibleToUser && hasImage && !frozen && !idlePaused && !isVideo
        if (should && !motionRunning) {
            motionRunning = true
            lastFrameNs = 0L
            motionFrame.animate().cancel()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else if (!should && motionRunning) {
            stopMotion()
        }
        if (!cinematic) applyStaticFrame()
    }

    private fun stopMotion() {
        motionRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!cinematic) applyStaticFrame() else applyFrame()
    }

    /** Draws one bitmap centre-cropped to the view. No allocation in onDraw. */
    private class ImageLayer(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val m = Matrix()
        var bitmap: Bitmap? = null
            set(v) {
                field = v
                computeMatrix()
                invalidate()
            }

        init { setWillNotDraw(false) }

        private fun computeMatrix() {
            val b = bitmap ?: return
            if (width == 0 || height == 0) return
            val s = max(width.toFloat() / b.width, height.toFloat() / b.height)
            m.setScale(s, s)
            m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeMatrix()

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            canvas.drawBitmap(b, m, paint)
        }

        override fun hasOverlappingRendering() = false
    }
}
