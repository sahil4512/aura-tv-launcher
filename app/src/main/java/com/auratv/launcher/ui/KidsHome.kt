package com.auratv.launcher.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.R
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/** Native TV focus and recycled cards. Only visible apps request artwork. */
class KidsHome(private val activity: MainActivity) : LinearLayout(activity) {
    private val gap = 12.dp
    private val maxColumns = 4
    private val available = resources.displayMetrics.widthPixels - 2 * activity.safeH
    private val cardWidth = (available - (maxColumns - 1) * gap) / maxColumns
    private val artWidth = cardWidth - 12.dp
    private val artHeight = artWidth * 9 / 16
    private val cardHeight = artHeight + 47.dp
    private val exit = familyButton(activity, "Exit kids mode", compact = true)
    private val body = FrameLayout(activity)
    private var entries = emptyList<AppEntry>()
    private var bound = false
    private val cards = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
            ((recycled as? Card) ?: Card()).apply { bind(getItem(position)) }
    }
    private val apps = GridView(activity).apply {
        numColumns = maxColumns
        stretchMode = GridView.NO_STRETCH
        columnWidth = cardWidth
        horizontalSpacing = gap
        verticalSpacing = gap
        isVerticalScrollBarEnabled = false
        selector = familySurface(Style.accent, Color.TRANSPARENT, 15)
        adapter = cards
        setOnItemClickListener { _, card, position, _ -> activity.launch(entries[position], card) }
        setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP &&
                selectedItemPosition in 0 until numColumns) exit.requestFocus() else false
        }
    }
    private val empty = TextView(activity).apply {
        text = "No apps selected\nExit kids mode to choose apps."
        gravity = Gravity.CENTER
        sizeDp(20f)
        setTextColor(Style.theme.textDim)
    }

    init {
        orientation = VERTICAL
        setPadding(activity.safeH, activity.safeV, activity.safeH, activity.safeV)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Style.theme.placeholder, Style.theme.shade, Style.theme.shade))
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_kids) }, LayoutParams(40.dp, 40.dp))
        header.addView(TextView(activity).apply {
            text = "Kids mode"
            sizeDp(30f)
            typeface = Style.MEDIUM
            setTextColor(Style.theme.text)
        }, LayoutParams(0, -2, 1f).apply { leftMargin = 12.dp })
        exit.setOnClickListener { activity.exitKidsMode() }
        exit.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && entries.isNotEmpty())
                apps.requestFocus() else false
        }
        header.addView(exit, LayoutParams(138.dp, 40.dp))
        addView(header)
        body.addView(apps)
        body.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        addView(body, LayoutParams(-1, 0, 1f).apply { topMargin = 18.dp })
    }

    fun bind() {
        val allowed = activity.family.allowed
        val next = AppOrdering.sortForAllApps(activity.repo.apps.filter { it.packageName in allowed }, 0)
        if (bound && next == entries) return
        val selected = entries.getOrNull(apps.selectedItemPosition)?.packageName
        val keepExit = entries.isNotEmpty() && exit.isFocused
        bound = true
        entries = next
        cards.notifyDataSetChanged()
        val columns = entries.size.coerceIn(1, maxColumns)
        apps.numColumns = columns
        val shortRow = entries.size <= maxColumns
        apps.layoutParams = FrameLayout.LayoutParams(columns * cardWidth + (columns - 1) * gap,
            if (shortRow) cardHeight else -1, if (shortRow) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        apps.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (keepExit || entries.isEmpty()) exit.requestFocus() else {
            apps.requestFocus()
            apps.setSelection(entries.indexOfFirst { it.packageName == selected }.coerceAtLeast(0))
        }
    }

    private inner class Card : LinearLayout(activity) {
        private var entry: AppEntry? = null
        private val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = familySurface(Style.theme.placeholder, Color.TRANSPARENT, 10)
            clipToOutline = true
        }
        private val name = TextView(activity).apply {
            gravity = Gravity.CENTER
            typeface = Style.MEDIUM
            sizeDp(16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Style.theme.text)
        }

        init {
            layoutParams = AbsListView.LayoutParams(-1, cardHeight)
            orientation = VERTICAL
            // Leave a gutter for GridView's selection outline.
            val surface = familySurface(Style.theme.placeholder, Color.TRANSPARENT, 12)
            background = android.graphics.drawable.InsetDrawable(surface, 3.dp)
            setPadding(6.dp, 6.dp, 6.dp, 4.dp)
            addView(art, LayoutParams(-1, artHeight))
            addView(name, LayoutParams(-1, 0, 1f))
        }

        fun bind(app: AppEntry) {
            if (entry == app) return
            entry = app
            name.text = app.label
            contentDescription = app.label
            val w = artWidth.coerceAtMost(168.dp)
            val hit = activity.tiles.get(app, w, w * 9 / 16) { bitmap ->
                if (entry == app) art.setImageBitmap(bitmap)
            }
            art.setImageBitmap(hit)
        }
    }
}
