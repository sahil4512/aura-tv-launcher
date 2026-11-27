package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import com.auratv.launcher.util.dp

/**
 * The card artwork. Rounded corners come from a hardware outline clip and focus scaling is a
 * RenderNode property, so focus animations never redraw the bitmap.
 */
class TileView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(v) { field = v; isLight = v != null && meanLuma(v) > FocusSpec.LIGHT_CARD_LUMA; invalidate() }
    /** The artwork is light (e.g. a white banner): its focus glow is toned down. */
    var isLight = false
        private set
    var label: String = ""
        set(v) { field = v; invalidate() }
    var kind = KIND_APP
        set(v) { field = v; invalidate() }
    /** Recently-used style: the name sits inside the card over a soft bottom fade. */
    var innerLabel = false
        set(v) { field = v; invalidate() }
    var focusRingColor: Int? = null
    var ringVisible = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var moving = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fade = Paint()
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dst = RectF()
    private val tmp = RectF()
    private val path = Path()

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, Style.theme.cornerDp.dp)
            }
        }
        clipToOutline = true
    }

    fun onThemeChanged() {
        invalidateOutline()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        fade.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
            0, Style.withAlpha(0, 170), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val t = Style.theme
        val w = width.toFloat()
        val h = height.toFloat()
        val b = bitmap
        when {
            kind == KIND_APP && b != null -> c.drawBitmap(b, null, dst, bmpPaint)
            kind == KIND_APP -> {
                fill.color = t.placeholder; c.drawRect(dst, fill)
                if (!innerLabel) drawCentered(c, label, w, h, 0.15f)
            }
            else -> {
                // Glass card for the "+ Add" and "All Apps" actions.
                fill.color = Style.withAlpha(t.text, 22); c.drawRect(dst, fill)
                if (kind == KIND_ADD) drawPlus(c, w, h) else drawGrid(c, w, h)
            }
        }
        if (innerLabel && label.isNotEmpty()) {
            c.drawRect(0f, h * 0.45f, w, h, fade)
            text.color = Style.withAlpha(t.text, 240)
            text.textSize = h * 0.155f
            text.typeface = Style.MEDIUM
            val s = TextUtils.ellipsize(label, text, w * 0.88f, TextUtils.TruncateAt.END)
            c.drawText(s, 0, s.length, w / 2f, h - h * 0.11f, text)
        }
        // Glass edge on every card; a crisp ring when focused.
        val r = t.cornerDp.dp
        if (ringVisible || moving) {
            val rw = if (focusRingColor != null) 4f.dp else (if (moving) FocusSpec.movingRingDp(t.ringWidthDp) else FocusSpec.ringDp(t.ringWidthDp)).dp
            stroke.strokeWidth = rw
            stroke.color = if (moving) Style.accent else focusRingColor ?: t.ring
            tmp.set(rw / 2f, rw / 2f, w - rw / 2f, h - rw / 2f)
            c.drawRoundRect(tmp, r - rw / 2f, r - rw / 2f, stroke)
        } else {
            stroke.strokeWidth = 1f.dp
            stroke.color = Style.withAlpha(t.text, 30)
            tmp.set(0.5f.dp, 0.5f.dp, w - 0.5f.dp, h - 0.5f.dp)
            c.drawRoundRect(tmp, r, r, stroke)
        }
        if (moving) drawMoveArrows(c, w, h)
    }

    private fun drawCentered(c: Canvas, s0: String, w: Float, h: Float, size: Float) {
        text.color = Style.withAlpha(Style.theme.text, 210)
        text.textSize = h * size
        text.typeface = Style.MEDIUM
        val s = TextUtils.ellipsize(s0, text, w * 0.86f, TextUtils.TruncateAt.END)
        c.drawText(s, 0, s.length, w / 2f, h / 2f + text.textSize / 3f, text)
    }

    private fun drawPlus(c: Canvas, w: Float, h: Float) {
        stroke.color = Style.withAlpha(Style.theme.text, 220)
        stroke.strokeWidth = 2.2f.dp
        stroke.strokeCap = Paint.Cap.ROUND
        val s = h * 0.17f
        c.drawLine(w / 2 - s, h / 2, w / 2 + s, h / 2, stroke)
        c.drawLine(w / 2, h / 2 - s, w / 2, h / 2 + s, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawGrid(c: Canvas, w: Float, h: Float) {
        fill.color = Style.withAlpha(Style.theme.text, 225)
        val cell = h * 0.13f
        val gap = h * 0.07f
        val total = cell * 2 + gap
        val x0 = (w - total) / 2f
        val y0 = (if (innerLabel) h * 0.40f else h * 0.5f) - total / 2f
        for (r in 0 until 2) for (col in 0 until 2) {
            tmp.set(x0 + col * (cell + gap), y0 + r * (cell + gap), x0 + col * (cell + gap) + cell, y0 + r * (cell + gap) + cell)
            c.drawRoundRect(tmp, cell * 0.28f, cell * 0.28f, fill)
        }
    }

    private fun drawMoveArrows(c: Canvas, w: Float, h: Float) {
        fill.color = Style.accent
        val s = h * 0.09f
        val cy = h / 2f
        path.reset()
        path.moveTo(s * 0.8f, cy); path.lineTo(s * 1.8f, cy - s); path.lineTo(s * 1.8f, cy + s); path.close()
        path.moveTo(w - s * 0.8f, cy); path.lineTo(w - s * 1.8f, cy - s); path.lineTo(w - s * 1.8f, cy + s); path.close()
        c.drawPath(path, fill)
    }

    /** The Recently Used style draws the name inside the card: true when it is cut with "…". */
    fun isLabelTruncated(): Boolean {
        if (!innerLabel || width == 0) return false
        text.textSize = height * 0.155f
        text.typeface = Style.MEDIUM
        return text.measureText(label) > width * 0.88f
    }

    /** Average brightness from a 5×5 sample grid: 25 pixel reads once per bitmap, not per frame. */
    private fun meanLuma(b: Bitmap): Float {
        if (b.width < 5 || b.height < 5) return 0f
        var sum = 0f
        for (i in 0 until 5) for (j in 0 until 5) {
            val px = b.getPixel(b.width * (2 * i + 1) / 10, b.height * (2 * j + 1) / 10)
            sum += (0.2126f * android.graphics.Color.red(px) + 0.7152f * android.graphics.Color.green(px) +
                0.0722f * android.graphics.Color.blue(px)) / 255f
        }
        return sum / 25f
    }

    override fun hasOverlappingRendering() = false

    companion object {
        const val KIND_APP = 0
        const val KIND_ALL_APPS = 1
        const val KIND_ADD = 2
    }
}
