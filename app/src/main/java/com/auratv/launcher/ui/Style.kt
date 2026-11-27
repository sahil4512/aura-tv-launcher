package com.auratv.launcher.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import com.auratv.launcher.settings.AuraTheme
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.util.dp

/** Current visual parameters, derived from preferences. Read by every view. */
object Style {
    fun dockBackground(backdrop: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        setColor(android.graphics.Color.argb(if (backdrop) 30 else 46, 255, 255, 255))
        cornerRadius = 22f.dp
        setStroke(1.dp, android.graphics.Color.argb(if (backdrop) 40 else 56, 255, 255, 255))
    }

    var theme: AuraTheme = AuraTheme.MIDNIGHT
    var animFactor = 1f
    /** Accent: the theme's own, or the user's pick from the Appearance swatches. */
    var accent = theme.accent
    /** Soft halo behind the focused card. */
    var glowColor = 0

    val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    /** Handwritten tagline; Android maps "cursive" to Dancing Script. Falls back to serif italic. */
    val SCRIPT: Typeface = Typeface.create("cursive", Typeface.NORMAL).let {
        if (it == Typeface.DEFAULT) Typeface.create(Typeface.SERIF, Typeface.ITALIC) else it
    }
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    val easeOut: Interpolator = DecelerateInterpolator(1.6f)

    /** Favourite card widths in dp for Small / Medium / Large / Extra large. Cards are 4:3. */
    val TILE_WIDTHS = intArrayOf(92, 104, 116, 132)
    val SIZE_NAMES = listOf("Small", "Medium", "Large", "Extra large")

    /** Swatches offered in Appearance (0 = theme default). */
    val ACCENTS = intArrayOf(0, 0xFF4A90FF.toInt(), 0xFF34C38F.toInt(), 0xFF6F8FC7.toInt(), 0xFFE84FA8.toInt(),
        0xFFF2557A.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFFFFF.toInt())

    fun load(p: Prefs) {
        theme = AuraTheme.byId(p.themeId)
        animFactor = Prefs.UI_ANIM_FACTORS[p.uiAnimSpeed.coerceIn(0, 3)]
        accent = if (p.accentColor != 0) p.accentColor else theme.accent
        // Glow: the accent lifted toward white so it reads as light, not as a coloured blob.
        glowColor = Color.argb(255, (Color.red(accent) + 255 * 2) / 3, (Color.green(accent) + 255 * 2) / 3, (Color.blue(accent) + 255 * 2) / 3)
    }

    /** Recently-used cards: a little narrower and much flatter, name inside. */
    fun recentW(sizeIdx: Int) = (tileW(sizeIdx) * 0.92f).toInt()
    fun recentH(sizeIdx: Int) = (tileW(sizeIdx) * 0.92f * 0.70f).toInt()

    fun dur(ms: Long): Long = (ms * animFactor).toLong()

    /** Home's favourite cards: wide 16:9 banners in one dock row. */
    val FAV_WIDTHS = intArrayOf(150, 170, 190, 214)
    fun favW(sizeIdx: Int) = FAV_WIDTHS[sizeIdx.coerceIn(0, 3)].dp
    fun favH(sizeIdx: Int) = favW(sizeIdx) * 9 / 16

    fun tileW(sizeIdx: Int) = TILE_WIDTHS[sizeIdx.coerceIn(0, 3)].dp
    fun tileH(sizeIdx: Int) = tileW(sizeIdx) * 3 / 4

    fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Mark colour on top of [c]: white, except on near-white colours (e.g. Minimal Dark's accent). */
    fun contrastOn(c: Int): Int {
        val l = 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)
        return if (l > 200f) Color.argb(230, 16, 18, 24) else Color.WHITE
    }

    fun panelBackground(radiusDp: Float = 18f, opaque: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(if (opaque) withAlpha(theme.panel, 255) else theme.panel)
        cornerRadius = radiusDp * com.auratv.launcher.util.Dp.density
        setStroke(1.dp, theme.panelStroke)
    }

    /** The one accent highlight for focused rows, sidebar entries and buttons ([FocusSpec]). */
    fun focusHighlight(): GradientDrawable = GradientDrawable().apply {
        setColor(withAlpha(accent, FocusSpec.FILL_ALPHA))
        cornerRadius = 10f * com.auratv.launcher.util.Dp.density
        setStroke(1.dp, withAlpha(accent, FocusSpec.STROKE_ALPHA))
    }

    fun rowBackground(focused: Boolean): GradientDrawable? = if (focused) focusHighlight() else null
}
