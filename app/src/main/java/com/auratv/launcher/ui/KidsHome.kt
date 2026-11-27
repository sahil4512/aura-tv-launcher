package com.auratv.launcher.ui

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/** The kids layout is the entire activity content. Only approved packages get launch actions. */
class KidsHome(private val activity: MainActivity) : LinearLayout(activity) {
    private val exit = Button(activity).apply {
        text = "Exit kids mode"
        setOnClickListener { activity.exitKidsMode() }
    }
    private val apps = HomeGrid(activity).apply {
        spacing = 14.dp
        rowGap = 20.dp
        topPadding = 10.dp
        upExit = { exit }
    }
    private val empty = TextView(activity).apply {
        text = "No apps selected"
        setTextColor(Style.theme.textDim)
    }
    init {
        orientation = VERTICAL
        setPadding(activity.safeH, activity.safeV, activity.safeH, activity.safeV)
        setBackgroundColor(Style.theme.shade)
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "Kids mode"; sizeDp(28f); setTextColor(Style.theme.text)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(exit)
        })
        addView(empty)
        addView(apps, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    fun bind() {
        val focused = (findFocus() as? TileCell)?.entry?.packageName
        val keepExit = exit.isFocused
        val allowed = activity.family.allowed
        val entries = AppOrdering.sortForAllApps(activity.repo.apps.filter { it.packageName in allowed }, 0)
        val width = (resources.displayMetrics.widthPixels - 2 * activity.safeH - 3 * apps.spacing) / 4 - TileCell.GLOW_PAD.dp
        apps.setCells(entries.map { e ->
            TileCell(activity, e, TileView.KIND_APP).apply {
                bindSize(width, width * 9 / 16, true)
                activity.tiles.get(e, width, width * 9 / 16) { tile.bitmap = it }?.let { tile.bitmap = it }
                isLongClickable = false
                setOnClickListener { activity.launch(e, this) }
            }
        })
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        if (keepExit) exit.requestFocus() else (apps.cellAt(entries.indexOfFirst { it.packageName == focused }.coerceAtLeast(0)) ?: exit).requestFocus()
    }
}
