package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.util.Dp
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/**
 * A focusable card: soft glow (behind) + artwork + optional name below.
 * Focus = scale + ring + glow fade-in; all RenderNode properties, so it costs no redraw of the art.
 */
class TileCell(context: Context, val entry: AppEntry?, kind: Int) : FrameLayout(context) {

    val tile = TileView(context).apply { this.kind = kind }
    private val glow = View(context)
    val label = TextView(context).apply {
        sizeDp(14f)
        typeface = Style.REGULAR
        gravity = Gravity.CENTER_HORIZONTAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    var focusListener: ((TileCell, Boolean) -> Unit)? = null
    private var showOuterLabel = true

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        isLongClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        glow.alpha = 0f
        addView(glow)
        addView(tile)
        addView(label)
        val name = entry?.label ?: when (kind) { TileView.KIND_ADD -> "Add"; TileView.KIND_ALL_APPS -> "All Apps"; else -> "" }
        tile.label = name
        label.text = name
        contentDescription = name
        applyTheme()
    }

    /** [innerLabel]: name drawn inside the card (Recently Used); otherwise below it (Favourites). */
    fun bindSize(w: Int, h: Int, showLabel: Boolean, innerLabel: Boolean = false) {
        showOuterLabel = showLabel && !innerLabel
        tile.innerLabel = innerLabel
        tile.layoutParams = LayoutParams(w, h)
        val pad = GLOW_PAD.dp
        glow.layoutParams = LayoutParams(w + pad * 2, h + pad * 2).apply { leftMargin = -pad; topMargin = -pad }
        glow.background = glowDrawable(w, h)
        label.layoutParams = LayoutParams(w + 16.dp, LayoutParams.WRAP_CONTENT).apply {
            topMargin = h + 9.dp; leftMargin = -8.dp
        }
        label.visibility = if (showOuterLabel) View.VISIBLE else View.GONE
        tile.pivotX = w / 2f; tile.pivotY = h / 2f
        glow.pivotX = w / 2f + pad; glow.pivotY = h / 2f + pad
    }

    fun applyTheme() {
        label.setTextColor(if (isFocused) Style.theme.text else Style.withAlpha(Style.theme.text, 200))
        (glow.background as? BitmapDrawable)?.setTint(Style.withAlpha(Style.glowColor, 255))
        tile.onThemeChanged()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val t = Style.theme
        tile.ringVisible = gainFocus
        label.setTextColor(if (gainFocus) t.text else Style.withAlpha(t.text, 200))
        label.typeface = if (gainFocus) Style.MEDIUM else Style.REGULAR
        val s = if (gainFocus) t.focusScale else 1f
        val d = Style.dur(if (gainFocus) FocusSpec.IN_MS else FocusSpec.OUT_MS)
        tile.animate().scaleX(s).scaleY(s).setDuration(d).setInterpolator(Style.easeOut).start()
        val glowTo = if (gainFocus) FocusSpec.glowAlpha(tile.isLight) else 0f
        glow.animate().alpha(glowTo).scaleX(s).scaleY(s).setDuration(d).setInterpolator(Style.easeOut).start()
        label.animate().translationY(if (gainFocus) tile.height * (s - 1f) / 2f else 0f)
            .setDuration(d).setInterpolator(Style.easeOut).start()
        focusListener?.invoke(this, gainFocus)
    }

    /** Small horizontal shake when the user presses against the end of a row. */
    fun bump(direction: Int) {
        if (!FocusSpec.bumpEnabled(Style.animFactor)) return
        val dx = 8f * Dp.density * direction
        val s = Style.theme.focusScale
        tile.animate().cancel()
        tile.scaleX = s; tile.scaleY = s
        tile.animate().translationX(dx).setDuration(Style.dur(70)).withEndAction {
            tile.animate().translationX(0f).setDuration(Style.dur(220)).setInterpolator(Style.easeOut).start()
        }.start()
    }

    /** True when the app's name is shown cut with "…" (below the card, or inside it for recents). */
    fun isNameTruncated(): Boolean {
        if (tile.innerLabel) return tile.isLabelTruncated()
        val w = label.width - label.paddingLeft - label.paddingRight
        return label.visibility == View.VISIBLE && w > 0 && label.paint.measureText(label.text.toString()) > w
    }

    /**
     * Runs [block] now, or after this card's first layout. Focus can arrive before it (All Apps
     * focuses its first card as it opens), when the name has no width yet to be measured against.
     */
    fun whenLaidOut(block: () -> Unit) {
        if (isLaidOut) { block(); return }
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                removeOnLayoutChangeListener(this)
                block()
            }
        })
    }

    companion object {
        const val GLOW_PAD = 22

        private val glowCache = HashMap<Long, Bitmap>()

        /**
         * Soft glow mask rendered once per card size in software (BlurMaskFilter), stored as ALPHA_8
         * (1 byte/pixel) and tinted at draw time. Drawing it is a single textured quad.
         */
        fun glowDrawable(w: Int, h: Int): BitmapDrawable {
            val key = (w.toLong() shl 32) or h.toLong()
            val bmp = glowCache.getOrPut(key) {
                val pad = GLOW_PAD.dp
                val b = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ALPHA_8)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    maskFilter = BlurMaskFilter(pad * 0.62f, BlurMaskFilter.Blur.NORMAL)
                    alpha = FocusSpec.GLOW_MASK_ALPHA
                }
                val inset = pad * 0.55f
                Canvas(b).drawRoundRect(RectF(inset, inset, w + pad * 2 - inset, h + pad * 2 - inset),
                    Style.theme.cornerDp.dp + 6f, Style.theme.cornerDp.dp + 6f, p)
                b
            }
            return BitmapDrawable(null as android.content.res.Resources?, bmp).apply { setTint(Style.glowColor) }
        }
    }
}
