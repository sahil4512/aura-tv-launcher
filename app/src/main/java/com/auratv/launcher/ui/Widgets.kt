package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.auratv.launcher.util.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Darkens the wallpaper for legibility in ONE full-screen pass: uniform dim, top shade and bottom
 * shade are pre-combined into a single multi-stop vertical gradient. Every extra translucent
 * full-screen layer costs a whole-screen blend on every animated frame, so this matters on Fire TV.
 */
class ScrimView(context: Context) : View(context) {
    var dim = 0.2f
        set(v) { field = v; rebuild() }
    private val paint = Paint()

    fun rebuild() {
        val h = height.toFloat()
        if (h == 0f) return
        val t = Style.theme
        // Alpha-compose dim with each shade: a = 1 - (1 - dim)(1 - shade)
        fun over(shade: Float) = 1f - (1f - dim) * (1f - shade)
        val stops = floatArrayOf(0f, 0.32f, 0.40f, 0.70f, 1f)
        val alphas = floatArrayOf(over(t.topShade), dim, dim, over(t.bottomShade * 0.55f), over(t.bottomShade))
        val colors = IntArray(stops.size) { Style.withAlpha(t.shade, (255 * alphas[it]).toInt().coerceIn(0, 255)) }
        paint.shader = LinearGradient(0f, 0f, 0f, h, colors, stops, Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun hasOverlappingRendering() = false
}

/** Focusable round settings button with a drawn gear glyph (no icon assets needed). */
class GearButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gear = Path()
    private val hole = Path()

    init {
        isFocusable = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        contentDescription = "Settings"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gear.reset(); hole.reset()
        val cx = w / 2f; val cy = h / 2f
        val rOuter = w * 0.25f; val rInner = w * 0.19f
        val teeth = 8
        for (i in 0 until teeth * 2) {
            val a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / (teeth * 2)
            val a1 = a0 + Math.PI * 2 / (teeth * 2)
            val r = if (i % 2 == 0) rOuter else rInner
            val x0 = cx + r * cos(a0).toFloat(); val y0 = cy + r * sin(a0).toFloat()
            val x1 = cx + r * cos(a1).toFloat(); val y1 = cy + r * sin(a1).toFloat()
            if (i == 0) gear.moveTo(x0, y0) else gear.lineTo(x0, y0)
            gear.lineTo(x1, y1)
        }
        gear.close()
        hole.addCircle(cx, cy, w * 0.09f, Path.Direction.CW)
        gear.fillType = Path.FillType.EVEN_ODD
        gear.addPath(hole)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) FocusSpec.SMALL_SCALE else 1f
        animate().scaleX(s).scaleY(s).setDuration(Style.dur(if (gainFocus) FocusSpec.IN_MS else FocusSpec.OUT_MS))
            .setInterpolator(Style.easeOut).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val t = Style.theme
        val r = width / 2f
        if (isFocused) {
            paint.color = t.text
            c.drawCircle(r, r, r, paint)
            paint.color = Style.withAlpha(t.shade, 255)
        } else {
            paint.color = Style.withAlpha(t.shade, 110)
            c.drawCircle(r, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * com.auratv.launcher.util.Dp.density
            paint.color = Style.withAlpha(t.text, 90)
            c.drawCircle(r, r, r - paint.strokeWidth, paint)
            paint.style = Paint.Style.FILL
            paint.color = Style.withAlpha(t.text, 235)
        }
        c.drawPath(gear, paint)
    }
}

/** Thin progress-free spacer helper. */
fun spacer(ctx: Context, h: Int) = View(ctx).apply { minimumHeight = h.dp }

/**
 * Vertical scroller for overlay pages (Settings, All Apps).
 *
 * Content is clipped at the top and bottom edges, so scrolled rows never draw over the page title,
 * the brand or the clock. Left and right are not clipped, so a scaled focus card, its ring and glow
 * stay whole at the first and last columns. The clip is a RenderNode property (no extra layer).
 * [focusMargin] keeps the scaled ring/glow of a focused item inside the clip when it is scrolled
 * into view at either edge.
 */
class EdgeSafeScrollView(context: Context, private val focusMargin: Int, private val sideOverflow: Int) :
    android.widget.ScrollView(context) {

    private val clip = Rect()

    init {
        // On Android 7 a ScrollView is focusable itself; without this, ◀/▼ can park focus on the
        // invisible container and the page scrolls instead of moving between items.
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isVerticalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        clipChildren = false
        clipToPadding = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        clip.set(-sideOverflow, 0, width + sideOverflow, height)
        clipBounds = clip
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val padded = Rect(rect)
        padded.top -= focusMargin
        padded.bottom += focusMargin
        return super.computeScrollDeltaToGetChildRectOnScreen(padded)
    }
}

/**
 * Pill-shaped quick action on Home ("All Apps", the Wi-Fi network, "Settings"): a drawn glyph and a
 * short label. Focused = filled with the theme text colour (dark glyph), slightly enlarged.
 */
class ChipButton(context: Context, private val icon: Int, text: String) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * com.auratv.launcher.util.Dp.density
        typeface = Style.MEDIUM
    }
    private val glyph = Path()
    var text: String = text
        set(v) { if (field != v) { field = v; contentDescription = v; requestLayout(); invalidate() } }

    init {
        isFocusable = true
        isClickable = true
        contentDescription = text
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    private val padH get() = 14.dp
    private val iconBox get() = 15.dp
    private val gap get() = 8.dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = padH * 2 + iconBox + gap + fg.measureText(text).toInt()
        setMeasuredDimension(w, 34.dp)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) FocusSpec.SMALL_SCALE else 1f
        animate().scaleX(s).scaleY(s).setDuration(Style.dur(if (gainFocus) FocusSpec.IN_MS else FocusSpec.OUT_MS))
            .setInterpolator(Style.easeOut).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val t = Style.theme
        val h = height.toFloat()
        val r = h / 2f
        val ink: Int
        if (isFocused) {
            bg.style = Paint.Style.FILL
            bg.color = t.text
            c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, bg)
            ink = Color.rgb(16, 16, 22)
        } else {
            bg.style = Paint.Style.FILL
            bg.color = Color.argb(70, 0, 0, 0)
            c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, bg)
            bg.style = Paint.Style.STROKE
            bg.strokeWidth = 1f * com.auratv.launcher.util.Dp.density
            bg.color = Style.withAlpha(t.text, 70)
            val s = bg.strokeWidth / 2
            c.drawRoundRect(s, s, width - s, h - s, r, r, bg)
            ink = Style.withAlpha(t.text, 235)
        }
        val ix = padH.toFloat()
        val iy = (h - iconBox) / 2f
        drawGlyph(c, ix, iy, iconBox.toFloat(), ink)
        fg.color = ink
        val baseline = h / 2f - (fg.descent() + fg.ascent()) / 2f
        c.drawText(text, ix + iconBox + gap, baseline, fg)
    }

    private fun drawGlyph(c: Canvas, x: Float, y: Float, s: Float, ink: Int) {
        val p = bg
        p.color = ink
        p.strokeWidth = 1.6f * com.auratv.launcher.util.Dp.density
        p.strokeCap = Paint.Cap.ROUND
        when (icon) {
            ICON_GRID -> {
                p.style = Paint.Style.FILL
                val cell = s * 0.4f; val g = s * 0.2f
                for (i in 0..1) for (j in 0..1) {
                    val l = x + i * (cell + g); val t = y + j * (cell + g)
                    c.drawRoundRect(l, t, l + cell, t + cell, cell * 0.25f, cell * 0.25f, p)
                }
            }
            ICON_WIFI, ICON_WIFI_OFF -> {
                p.style = Paint.Style.STROKE
                val cx = x + s / 2; val cy = y + s * 0.92f
                for (k in 1..3) {
                    val rad = s * 0.28f * k
                    c.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.04f, s * 0.08f, p)
                if (icon == ICON_WIFI_OFF) {
                    p.style = Paint.Style.STROKE
                    c.drawLine(x + s * 0.1f, y + s * 0.1f, x + s * 0.9f, y + s * 0.9f, p)
                }
            }
            ICON_GEAR -> {
                glyph.reset()
                val cx = x + s / 2; val cy = y + s / 2
                val ro = s * 0.5f; val ri = s * 0.36f
                val teeth = 8
                for (i in 0 until teeth * 2) {
                    val a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / (teeth * 2)
                    val a1 = a0 + Math.PI * 2 / (teeth * 2)
                    val rr = if (i % 2 == 0) ro else ri
                    val x0 = cx + rr * cos(a0).toFloat(); val y0 = cy + rr * sin(a0).toFloat()
                    val x1 = cx + rr * cos(a1).toFloat(); val y1 = cy + rr * sin(a1).toFloat()
                    if (i == 0) glyph.moveTo(x0, y0) else glyph.lineTo(x0, y0)
                    glyph.lineTo(x1, y1)
                }
                glyph.close()
                glyph.addCircle(cx, cy, s * 0.15f, Path.Direction.CW)
                glyph.fillType = Path.FillType.EVEN_ODD
                p.style = Paint.Style.FILL
                c.drawPath(glyph, p)
            }
        }
    }

    companion object {
        const val ICON_GRID = 0
        const val ICON_WIFI = 1
        const val ICON_WIFI_OFF = 2
        const val ICON_GEAR = 3
    }
}
