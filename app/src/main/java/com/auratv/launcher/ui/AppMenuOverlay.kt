package com.auratv.launcher.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/** Long-press / MENU options for one app. */
class AppMenuOverlay(
    activity: MainActivity,
    private val entry: AppEntry,
    private val inFavoritesRow: Boolean,
) : Overlay(activity) {

    private val rows = ArrayList<MenuRow>()

    override val root: View = FrameLayout(ctx).apply {
        setBackgroundColor(Style.withAlpha(Style.theme.shade, 140))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.panelBackground()
            setPadding(22.dp, 22.dp, 22.dp, 16.dp)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14.dp)
        }
        val tile = TileView(ctx)
        val th = 72.dp; val tw = th * 16 / 9
        activity.tiles.get(entry, Style.tileW(activity.prefs.iconSize), Style.tileH(activity.prefs.iconSize)) { tile.bitmap = it }
            ?.let { tile.bitmap = it }
        tile.label = entry.label
        header.addView(tile, LinearLayout.LayoutParams(tw, th))
        header.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 0, 0, 0)
            addView(TextView(ctx).apply {
                text = entry.label; sizeDp(22f); typeface = Style.REGULAR; setTextColor(Style.theme.text)
            })
            addView(TextView(ctx).apply {
                text = entry.packageName; sizeDp(12f); setTextColor(Style.theme.textDim)
            })
        })
        card.addView(header)

        val isFav = activity.isFavorite(entry)
        add(card, "Open") { activity.launch(entry, null) }
        if (isFav) {
            if (inFavoritesRow) add(card, "Move", "Use ◀ ▶ to reposition, Select to finish") { activity.startMove(entry) }
            add(card, "Remove from Favourites") { activity.setFavorite(entry, false) }
        } else {
            add(card, "Add to Favourites") { activity.setFavorite(entry, true) }
        }
        add(card, "Hide from Home", "Restore it any time in Settings → Apps") { activity.setHidden(entry, true) }
        add(card, "App info") { activity.repo.openAppInfo(entry) }
        if (activity.repo.canUninstall(entry)) add(card, "Uninstall") { activity.repo.uninstall(entry) }

        addView(card, FrameLayout.LayoutParams(440.dp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun add(parent: LinearLayout, title: String, sub: String? = null, action: () -> Unit) {
        val r = MenuRow(ctx)
        r.title.text = title
        r.setSubtitle(sub)
        r.setOnClickListener { close(); action() }
        rows.add(r)
        parent.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun focusInitial() { rows.firstOrNull()?.requestFocus() }
}
