package com.auratv.launcher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Horizontal row of [TileCell]s with deterministic DPAD behaviour:
 *  - LEFT/RIGHT always move exactly one tile and stop (with a bump) at the ends — no wrapping, no jumps.
 *  - UP/DOWN are delegated to [verticalExit] so the activity decides the target explicitly.
 *  - The strip scrolls by animating translationX (a RenderNode property: no relayout, no redraw).
 */
class HomeRow(context: Context) : ViewGroup(context) {

    val strip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    var spacing = 0
    var verticalExit: ((direction: Int, fromIndex: Int) -> View?)? = null
    var onCellFocused: ((index: Int) -> Unit)? = null
    /** False inside [HomeGrid]: the row always fits, so it never scrolls sideways. */
    var scrollable = true
    private var offset = 0f

    init {
        clipChildren = false
        clipToPadding = false
        addView(strip)
    }

    val cellCount get() = strip.childCount
    fun cellAt(i: Int): TileCell? = strip.getChildAt(i) as? TileCell
    fun indexOf(v: View): Int = strip.indexOfChild(v)

    fun setCells(cells: List<TileCell>) {
        strip.removeAllViews()
        cells.forEachIndexed { i, c ->
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = spacing
            strip.addView(c, lp)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        strip.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            strip.measuredHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        strip.layout(paddingLeft, paddingTop, paddingLeft + strip.measuredWidth, paddingTop + strip.measuredHeight)
        // Keep the current focus visible after a relayout (e.g. icon size change).
        val f = strip.findFocus()
        if (f != null) scrollToIndex(indexOf(f), false)
        else if (offset > maxOffset()) { offset = maxOffset(); strip.translationX = -offset }
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val idx = strip.indexOfChild(focused)
        if (idx >= 0) {
            when (direction) {
                View.FOCUS_LEFT -> return if (idx > 0) strip.getChildAt(idx - 1) else { cellAt(idx)?.bump(-1); focused }
                View.FOCUS_RIGHT -> return if (idx < strip.childCount - 1) strip.getChildAt(idx + 1) else { cellAt(idx)?.bump(1); focused }
                View.FOCUS_UP, View.FOCUS_DOWN -> verticalExit?.invoke(direction, idx)?.let { return it }
            }
        }
        return super.focusSearch(focused, direction)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val idx = focused?.let { strip.indexOfChild(it) } ?: -1
        if (idx >= 0) {
            scrollToIndex(idx, true)
            onCellFocused?.invoke(idx)
        }
    }

    private fun viewport() = width - paddingLeft - paddingRight
    private fun maxOffset() = (strip.measuredWidth - viewport()).coerceAtLeast(0).toFloat()

    /** Keeps the focused tile inside the viewport with one tile of look-ahead on either side. */
    fun scrollToIndex(idx: Int, animate: Boolean) {
        if (!scrollable) return
        val cell = strip.getChildAt(idx) ?: return
        if (width == 0) return
        val vp = viewport()
        val lead = (cell.width + spacing) * 0.9f
        var off = offset
        if (cell.left - off < lead) off = cell.left - lead
        else if (cell.right - off > vp - lead) off = cell.right - vp + lead
        off = off.coerceIn(0f, maxOffset())
        if (idx == 0) off = 0f
        if (off == offset && strip.translationX == -off) return
        offset = off
        if (animate && Style.animFactor > 0f) {
            strip.animate().translationX(-off).setDuration(Style.dur(FocusSpec.SCROLL_MS)).setInterpolator(Style.easeOut).start()
        } else {
            strip.animate().cancel()
            strip.translationX = -off
        }
    }

    /** Horizontal centre of a cell in window coordinates (after scrolling), for UP/DOWN matching. */
    fun cellCenterX(idx: Int): Float {
        val c = strip.getChildAt(idx) ?: return 0f
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return loc[0] + paddingLeft - offset + c.left + c.width / 2f
    }

    fun nearestIndexTo(x: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until strip.childCount) {
            val d = kotlin.math.abs(cellCenterX(i) - x)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
