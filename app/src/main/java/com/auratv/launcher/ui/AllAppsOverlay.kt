package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.util.Dp
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/**
 * Grid of every visible app (built on demand, discarded on close).
 * [pickFavorites] = opened from the "+ Add" card: Select pins/unpins instead of launching.
 */
class AllAppsOverlay(activity: MainActivity, private val pickFavorites: Boolean = false) : Overlay(activity) {

    override val coversWallpaper = true
    private val grid = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    // Clipped top and bottom so scrolled rows never draw over the title, brand or clock; open at the
    // sides so the first and last columns keep their full focus glow. The margin covers the glow.
    private val scroll = EdgeSafeScrollView(ctx, focusMargin = (TileCell.GLOW_PAD + 4).dp, sideOverflow = activity.safeH).apply {
        addView(grid)
    }
    private val count = TextView(ctx).apply { sizeDp(15f) }
    private val hint = TextView(ctx).apply {
        sizeDp(14f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.END
    }
    private val title = TextView(ctx).apply { sizeDp(19f); typeface = Style.MEDIUM }
    private val cells = ArrayList<TileCell>()

    override val root: View = FrameLayout(ctx).apply {
        addView(Backdrop(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.safeH, 104.dp, activity.safeH, 0)
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(title)
            addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 12.dp; bottomMargin = 2.dp
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 2.dp })
        }
        col.addView(header)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    init {
        title.text = if (pickFavorites) "Add to Favourites" else "All Apps"
        hint.text = hintText
        restyle()
        rebuild()
    }

    private fun restyle() {
        val t = Style.theme
        title.setTextColor(t.text)
        count.setTextColor(t.textDim)
        hint.setTextColor(t.textDim)
    }

    fun rebuild() {
        val p = activity.prefs
        val apps = AppOrdering.sortForAllApps(
            AppOrdering.visible(activity.repo.apps, p.hiddenSet(), p.showSystemApps), p.allAppsSort)
        count.text = "${apps.size}"
        val focusedKey = (grid.findFocus() as? TileCell)?.entry?.key
        grid.removeAllViews()
        cells.clear()
        val favs = p.favorites.toHashSet()
        val tw = Style.tileW(1)
        val th = Style.tileH(1)
        val gap = 30.dp
        val avail = activity.resources.displayMetrics.widthPixels - activity.safeH * 2
        val cols = ((avail + gap) / (tw + gap)).coerceAtLeast(2)
        var row: LinearLayout? = null
        apps.forEachIndexed { i, e ->
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply { clipChildren = false; clipToPadding = false }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (i == 0) 22.dp else 12.dp
                })
            }
            val cell = activity.makeCell(e, tw, th, showLabel = true)
            if (pickFavorites && e.key in favs) cell.label.text = "✓  " + e.label
            cell.setOnClickListener {
                if (pickFavorites) {
                    val pinned = e.key in activity.prefs.favorites
                    activity.setFavorite(e, !pinned)
                    cell.label.text = if (!pinned) "✓  " + e.label else e.label
                } else activity.launch(e, cell.tile)
            }
            cell.setOnLongClickListener { activity.openAppMenu(e, false); true }
            cell.focusListener = { c, has -> if (has) c.whenLaidOut { if (c.hasFocus()) showFullName(c) } }
            cells.add(cell)
            row!!.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i % cols != 0) leftMargin = gap
            })
        }
        grid.setPadding(0, 0, 0, 60.dp)
        if (focusedKey != null) cells.firstOrNull { it.entry?.key == focusedKey }?.requestFocus()
    }

    private val hintText get() = if (pickFavorites) "Select to pin or unpin · Back when done" else "Hold Select or press ≡ for options"

    /** Names stay on one line with "…"; the header's right-hand slot shows a cut name in full. */
    private fun showFullName(c: TileCell) {
        val full = c.entry?.label
        if (full != null && c.isNameTruncated()) {
            hint.text = full
            hint.setTextColor(Style.theme.text)
        } else {
            hint.text = hintText
            hint.setTextColor(Style.theme.textDim)
        }
    }

    fun focusedEntry(): AppEntry? = (grid.findFocus() as? TileCell)?.entry

    override fun focusInitial() { cells.firstOrNull()?.requestFocus() }

    override fun onThemeChanged() { restyle(); rebuild() }

    /** Same glass backdrop as Settings: Home's brand and clock stay visible above it. */
    private class Backdrop(c: Context) : View(c) {
        private val paint = Paint()

        override fun onDraw(cv: Canvas) {
            val t = Style.theme
            val top = 70f * Dp.density
            val solid = 104f * Dp.density
            paint.shader = LinearGradient(0f, top, 0f, solid, Style.withAlpha(t.panel, 0), Style.withAlpha(t.panel, 255), Shader.TileMode.CLAMP)
            cv.drawRect(0f, top, width.toFloat(), solid, paint)
            paint.shader = null
            paint.color = Style.withAlpha(t.panel, 255)
            cv.drawRect(0f, solid, width.toFloat(), height.toFloat(), paint)
            paint.color = Style.withAlpha(t.shade, 90)
            cv.drawRect(0f, 0f, width.toFloat(), top, paint)
        }
    }
}
