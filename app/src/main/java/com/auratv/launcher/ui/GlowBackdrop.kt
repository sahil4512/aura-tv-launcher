package com.auratv.launcher.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * "Ambient glow" background: a dark field lit by one soft radial glow in the focused app's own
 * colour (Netflix → red, Prime Video → blue). Two gradient fills; it redraws only while the colour
 * crossfades (a few hundred ms per focus change), never per frame otherwise.
 */
class GlowBackdrop(context: Context) : View(context) {

    private val base = Paint()
    private val glow = Paint()
    private var color = DEFAULT
    private var anim: ValueAnimator? = null

    /** Solid mode: one flat colour, no glow and no colour changes (nothing ever redraws). */
    var solid = false
        set(v) { if (field != v) { field = v; anim?.cancel(); anim = null; rebuild() } }

    /** Crossfades to [target]; [durationMs] 0 jumps. */
    fun setGlowColor(target: Int, durationMs: Long) {
        if (solid) return
        if (target == color && anim == null) return
        anim?.cancel()
        anim = null
        if (durationMs <= 0L || width == 0) {
            color = target
            rebuild()
            return
        }
        val from = color
        anim = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = durationMs
            interpolator = Style.easeOut
            addUpdateListener { color = it.animatedValue as Int; rebuild() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (anim === a) anim = null }
            })
            start()
        }
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return
        if (solid) {
            base.shader = null
            base.color = SOLID
            invalidate()
            return
        }
        base.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.rgb(12, 12, 18), Color.rgb(8, 8, 12), Color.rgb(4, 4, 7)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(w * 0.34f, h * 0.40f, w * 0.62f,
            intArrayOf(Style.withAlpha(color, 235), Style.withAlpha(color, 120), Style.withAlpha(color, 34), Color.TRANSPARENT),
            floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    override fun onDraw(c: Canvas) {
        if (solid) { c.drawColor(SOLID); return }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
    }

    override fun hasOverlappingRendering() = false

    companion object {
        val DEFAULT = Color.rgb(150, 24, 32)
        val SOLID = Color.rgb(13, 14, 20)

        /**
         * The colour a card "glows" with: the saturated colour in its artwork (weighted by how
         * saturated and bright each pixel is), pushed to a rich, mid-dark tone. Null when the art
         * is all greys (white/black logos); callers then keep a neutral accent.
         */
        fun colorOf(b: Bitmap?): Int? {
            if (b == null || b.isRecycled || b.width < 4 || b.height < 4) return null
            val hsv = FloatArray(3)
            var r = 0.0; var g = 0.0; var bl = 0.0; var weight = 0.0
            val cols = 24; val rows = 14
            for (j in 0 until rows) for (i in 0 until cols) {
                val p = b.getPixel((i + 0.5f).times(b.width / cols.toFloat()).toInt().coerceIn(0, b.width - 1),
                    (j + 0.5f).times(b.height / rows.toFloat()).toInt().coerceIn(0, b.height - 1))
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue
                val wgt = (hsv[1] * hsv[1] * hsv[2]).toDouble()
                r += Color.red(p) * wgt; g += Color.green(p) * wgt; bl += Color.blue(p) * wgt; weight += wgt
            }
            if (weight < 1.2) return null
            Color.colorToHSV(Color.rgb((r / weight).toInt(), (g / weight).toInt(), (bl / weight).toInt()), hsv)
            hsv[1] = hsv[1].coerceIn(0.62f, 0.95f)
            hsv[2] = 0.70f
            return Color.HSVToColor(hsv)
        }
    }
}
