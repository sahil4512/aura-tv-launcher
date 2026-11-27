package com.auratv.launcher.apps

import kotlin.math.roundToInt

/**
 * Brightness balance for app cards (pure, unit-tested). Applied once when a card is rendered and
 * cached, so it costs nothing per frame.
 *
 * Brand banners keep their own colours and logos untouched; only near-white backgrounds get a
 * light, uniform shade so they stop out-shining every other card (measured on the TV: white
 * banners ≈ luma 230 vs generated icon cards ≈ 65).
 */
object TileTone {
    /** Edge luma (0–1) above which a banner background counts as near-white. */
    const val WHITE_EDGE = 0.85f
    /** Maximum black overlay on a pure-white banner (≈12 %). */
    const val MAX_SHADE = 31
    /** HSV value of generated icon cards (was 0.42: noticeably darker than every banner). */
    const val ICON_CARD_VALUE = 0.50f

    fun luma(r: Int, g: Int, b: Int): Float = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f

    /** Alpha (0–255) of the black overlay for a banner whose edge colour has [edgeLuma]. */
    fun shadeAlpha(edgeLuma: Float): Int {
        if (edgeLuma <= WHITE_EDGE) return 0
        val t = ((edgeLuma - WHITE_EDGE) / (1f - WHITE_EDGE)).coerceIn(0f, 1f)
        return (t * MAX_SHADE + 0.5f).toInt()
    }

    fun isWideArtwork(w: Int, h: Int) = w > 0 && h > 0 && w.toFloat() / h >= 1.35f

    /** Fit the complete drawable. Some Fire TV apps declare a landscape launcher icon. */
    fun artworkSize(sourceW: Int, sourceH: Int, w: Int, h: Int, solidEdge: Boolean): Pair<Int, Int> {
        val sw = sourceW.coerceAtLeast(1)
        val sh = sourceH.takeIf { it > 0 } ?: sw
        val heightFraction = when {
            isWideArtwork(sourceW, sourceH) -> 1f
            solidEdge -> .86f
            else -> .72f
        }
        val scale = minOf(w.toFloat() / sw, h * heightFraction / sh)
        return (sw * scale).roundToInt().coerceAtLeast(1) to (sh * scale).roundToInt().coerceAtLeast(1)
    }
}
