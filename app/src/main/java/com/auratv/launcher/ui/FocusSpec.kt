package com.auratv.launcher.ui

/**
 * One focus language for every focusable element in Aura (pure values, unit-tested).
 *
 *  - Focused  = ring (theme colour) + scale + (app cards only) soft glow.
 *  - Selected = a ✓ badge (themes, wallpapers, swatches) — never a second ring, so a control that
 *    is both selected and focused shows both cues.
 *  - Highlighted rows (sidebar, menus, settings rows, buttons) share one accent fill and stroke.
 *
 * Everything animates RenderNode properties only (scale, alpha, translation): no blur, no shadow
 * layers, no per-frame redraw of artwork.
 */
object FocusSpec {
    /** Thinnest ring any theme may use: still clearly visible from a sofa at 1080p (4 px). */
    const val MIN_RING_DP = 2f
    fun ringDp(themeRingDp: Float): Float = maxOf(themeRingDp, MIN_RING_DP)
    /** Rearrange ("moving") ring: one step heavier than focus. */
    fun movingRingDp(themeRingDp: Float): Float = ringDp(themeRingDp) + 1f

    /** App cards use the theme's own scale; smaller targets one gentler, fixed scale. */
    const val SMALL_SCALE = 1.08f

    const val IN_MS = 180L
    const val OUT_MS = 140L
    /** Row scrolling and the Home column slide. */
    const val SCROLL_MS = 260L

    /** Accent highlight for rows, sidebar entries and buttons. */
    const val FILL_ALPHA = 56
    const val STROKE_ALPHA = 140

    /** Glow mask strength, and the glow's opacity on light cards (white banners bloom otherwise). */
    const val GLOW_MASK_ALPHA = 110
    const val GLOW_ON_LIGHT = 0.6f
    fun glowAlpha(lightCard: Boolean): Float = if (lightCard) GLOW_ON_LIGHT else 1f

    /** Mean luma (0–1) above which a card counts as light. */
    const val LIGHT_CARD_LUMA = 0.72f

    /** The end-of-row bump is motion for its own sake: none when animations are off. */
    fun bumpEnabled(animFactor: Float): Boolean = animFactor > 0f
}
