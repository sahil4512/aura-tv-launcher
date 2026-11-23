package com.auratv.launcher.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.auratv.launcher.util.dp

/**
 * Home's app grid: rows of [columns] cards that scroll vertically inside the dock.
 *  - ◀▶ move one card and stop at the row ends (each row is a [HomeRow], which never wraps).
 *  - ▲▼ go to the card straight above/below (nearest by position); ▲ from the top row leaves the
 *    grid through [upExit], ▼ on the last row stays put.
 *  - Scrolling animates translationY of the rows (a RenderNode property: no relayout, no redraw),
 *    keeping a slice of the next row in view so it's clear there is more.
 */
class HomeGrid(context: Context) : FrameLayout(context) {

    private val inner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val rows = ArrayList<HomeRow>()
    var columns = 4
    var spacing = 0
    /** Visual gap between rows; cards reserve [TileCell.GLOW_PAD] below them, which counts towards it. */
    var rowGap = 0
    var sidePadding = 0
    var topPadding = 0
    var upExit: ((x: Float) -> View?)? = null
    var onCellFocused: ((cell: TileCell, index: Int) -> Unit)? = null
    private var offset = 0f

    init {
        clipChildren = false
        clipToPadding = false
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    val cellCount get() = rows.sumOf { it.cellCount }
    fun cellAt(i: Int): TileCell? = if (i < 0) null else rows.getOrNull(i / columns)?.cellAt(i % columns)
    fun indexOf(v: View?): Int {
        rows.forEachIndexed { r, row -> val i = if (v == null) -1 else row.indexOf(v); if (i >= 0) return r * columns + i }
        return -1
    }
    fun contains(v: View?) = indexOf(v) >= 0

    fun setCells(cells: List<TileCell>) {
        val chunks = cells.chunked(columns.coerceAtLeast(1))
        while (rows.size > chunks.size) inner.removeView(rows.removeAt(rows.size - 1))
        while (rows.size < chunks.size) {
            val r = rows.size
            val row = HomeRow(context).apply {
                scrollable = false
                verticalExit = { dir, idx -> exit(r, dir, idx) }
                onCellFocused = { idx -> rowFocused(r, idx) }
            }
            rows.add(row)
            inner.addView(row)
        }
        chunks.forEachIndexed { r, chunk ->
            val row = rows[r]
            row.spacing = spacing
            row.setPadding(sidePadding, 0, sidePadding, 0)
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (r == 0) topPadding else rowGap - TileCell.GLOW_PAD.dp
            }
            row.setCells(chunk)
        }
        if (offset > maxOffset()) scrollTo(maxOffset(), false)
    }

    private fun exit(r: Int, dir: Int, idx: Int): View? {
        val x = rows[r].cellCenterX(idx)
        return when (dir) {
            View.FOCUS_UP -> if (r == 0) upExit?.invoke(x) else rows[r - 1].let { it.cellAt(it.nearestIndexTo(x)) }
            View.FOCUS_DOWN -> rows.getOrNull(r + 1)?.let { it.cellAt(it.nearestIndexTo(x)) } ?: rows[r].cellAt(idx)
            else -> null
        }
    }

    private fun rowFocused(r: Int, idx: Int) {
        val row = rows[r]
        row.cellAt(idx)?.let { onCellFocused?.invoke(it, r * columns + idx) }
        scrollToRow(r, true)
    }

    private fun maxOffset() = (inner.measuredHeight - height).coerceAtLeast(0).toFloat()

    /** Keeps row [r] fully visible with part of the neighbouring row showing (look-ahead). */
    fun scrollToRow(r: Int, animate: Boolean) {
        val row = rows.getOrNull(r) ?: return
        if (height == 0) return
        val lead = row.height * 0.35f
        var off = offset
        if (row.top - off < lead) off = row.top - lead
        else if (row.bottom - off > height - lead) off = row.bottom - height + lead
        if (r == 0) off = 0f
        scrollTo(off.coerceIn(0f, maxOffset()), animate)
    }

    private fun scrollTo(off: Float, animate: Boolean) {
        if (off == offset && inner.translationY == -off) return
        offset = off
        if (animate && Style.animFactor > 0f) {
            inner.animate().translationY(-off).setDuration(Style.dur(FocusSpec.SCROLL_MS)).setInterpolator(Style.easeOut).start()
        } else {
            inner.animate().cancel()
            inner.translationY = -off
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The rows are as tall as they need to be (taller than the grid when there are many apps):
        // measure them unbounded, then scroll within our own height.
        inner.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        val f = indexOf(findFocus())
        if (f >= 0) scrollToRow(f / columns, false) else if (offset > maxOffset()) scrollTo(maxOffset(), false)
    }
}
