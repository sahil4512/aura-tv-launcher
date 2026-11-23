package com.auratv.launcher.wallpaper

import kotlin.math.PI
import kotlin.math.sin

/**
 * Cinematic motion as a pure function of time.
 *
 * Instead of keyframes (which visibly "stop" at each keyframe), scale and pan are sums of slow
 * sinusoids with incommensurate periods. The motion is continuous in position *and* velocity,
 * never repeats exactly, and therefore loops seamlessly forever.
 *
 * Invariant (unit tested): the scaled image always covers the screen, including the extra
 * horizontal travel reserved for focus parallax, so no edge is ever revealed.
 */
object KenBurns {

    /** Zoom amplitude for Subtle / Medium / Strong. */
    val AMPLITUDE = floatArrayOf(0.035f, 0.065f, 0.10f)

    class Frame {
        var scale = 1f
        var tx = 0f
        var ty = 0f
    }

    /** Minimum scale that still covers the screen when shifted by [reserveX] px of parallax. */
    fun baseScale(width: Int, reserveX: Float): Float =
        if (width <= 0) 1f else 1f + 2f * reserveX / width + 0.004f

    /**
     * @param t seconds of motion time (already multiplied by the speed factor)
     * @param intensity 0..2
     * @param reserveX horizontal pixels reserved for parallax
     */
    fun compute(t: Double, intensity: Int, width: Int, height: Int, reserveX: Float, out: Frame): Frame {
        val amp = AMPLITUDE[intensity.coerceIn(0, AMPLITUDE.size - 1)]
        val base = baseScale(width, reserveX)
        val z = 0.55 + 0.45 * sin(2 * PI * t / 53.0 + 0.7)            // 0.10 .. 1.0
        val s = base + amp * z.toFloat()
        val maxTx = ((s - 1f) * width / 2f - reserveX).coerceAtLeast(0f)
        val maxTy = ((s - 1f) * height / 2f).coerceAtLeast(0f)
        val px = 0.62 * sin(2 * PI * t / 71.0) + 0.38 * sin(2 * PI * t / 43.0 + 1.3)
        val py = 0.60 * sin(2 * PI * t / 59.0 + 2.1) + 0.40 * sin(2 * PI * t / 37.0 + 0.4)
        out.scale = s
        out.tx = (maxTx * 0.92 * px).toFloat()
        out.ty = (maxTy * 0.85 * py).toFloat()
        return out
    }

    /** True if a frame keeps the image covering the viewport even with [parallax] px of shift. */
    fun covers(f: Frame, width: Int, height: Int, parallax: Float): Boolean {
        val spareX = (f.scale - 1f) * width / 2f
        val spareY = (f.scale - 1f) * height / 2f
        return kotlin.math.abs(f.tx) + parallax <= spareX + 0.01f && kotlin.math.abs(f.ty) <= spareY + 0.01f
    }
}
