package com.auratv.launcher.apps

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

    /** The glass top sheen brightens dark/colourful cards; on near-white ones it only adds glare. */
    fun wantsSheen(edgeLuma: Float): Boolean = edgeLuma <= WHITE_EDGE
}
