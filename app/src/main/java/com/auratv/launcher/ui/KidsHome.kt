package com.auratv.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.auratv.launcher.R
import java.util.TimeZone
import android.text.format.DateFormat
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/** The regular Home dock, scoped to the parent's approved apps. */
class KidsHome(private val activity: MainActivity) : FrameLayout(activity) {
    private val scenery = KidsScenery(activity)
    private val clock = TextView(activity).apply {
        typeface = Style.MEDIUM
        sizeDp(32f)
        includeFontPadding = false
        visibility = if (activity.prefs.showClock) View.VISIBLE else View.GONE
    }
    private val date = TextClock(activity).apply {
        format12Hour = "EEEE, d MMMM"
        format24Hour = format12Hour
        isAllCaps = true
        sizeDp(13f)
        letterSpacing = .08f
        includeFontPadding = false
        visibility = if (activity.prefs.showDate) View.VISIBLE else View.GONE
    }
    private val exit = TextView(activity).apply {
        text = "Grown-ups"
        typeface = Style.MEDIUM
        sizeDp(18f)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(16.dp, 0, 16.dp, 0)
        compoundDrawablePadding = 7.dp
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_grownups, 0, 0, 0)
    }
    private val ticker = MinuteTicker { updateScene() }
    private var clockMinute = -1
    private var use24Hours = false
    private var dayScene: Boolean? = null
    private val timeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_TIME_TICK) clockMinute = -1
            if (windowVisibility == View.VISIBLE) updateScene()
        }
    }
    private val title = TextView(activity).apply {
        sizeDp(36f)
        typeface = Style.MEDIUM
        setTextColor(Style.theme.text)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(activity.safeH, 0, activity.safeH, 0)
    }
    private val row = HomeRow(activity).apply {
        spacing = 18.dp - TileCell.GLOW_PAD.dp
        setPadding(18.dp, 18.dp, 18.dp, 0)
        verticalExit = { direction, index -> if (direction == View.FOCUS_UP) exit else cellAt(index) }
    }
    private val grid = HomeGrid(activity).apply {
        spacing = 18.dp - TileCell.GLOW_PAD.dp
        rowGap = 18.dp
        sidePadding = 18.dp
        topPadding = 18.dp
        upExit = { exit }
    }
    private val useGrid = activity.prefs.homeGrid
    private val dock = if (useGrid) grid else row
    private var entries: List<AppEntry>? = null
    private var selected = 0

    init {
        addView(scenery, LayoutParams(-1, -1))
        val timeBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.dp })
        }
        addView(timeBlock, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = activity.safeH
            topMargin = activity.safeV
        })
        addView(exit, LayoutParams(-2, 44.dp, Gravity.TOP or Gravity.END).apply {
            rightMargin = activity.safeH
            topMargin = activity.safeV
        })
        exit.setOnClickListener { activity.exitKidsMode() }
        exit.setOnKeyListener { _, code, event ->
            code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN &&
                cellAt(selected)?.requestFocus() == true
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        dock.clipToOutline = true
        column.addView(dock, LinearLayout.LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = activity.safeH - 18.dp
            rightMargin = leftMargin
            topMargin = 14.dp
        })
        addView(column, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (resources.displayMetrics.heightPixels *
                (if (useGrid || activity.prefs.rowPosition != 0) 0.29f else 0.27f)).toInt()
            bottomMargin = activity.safeV
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activity.registerReceiver(timeChanges, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        ticker.start()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && findFocus() == null) (cellAt(selected) ?: exit).requestFocusFromTouch()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow) ticker.start() else ticker.stop()
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        activity.unregisterReceiver(timeChanges)
        dayScene = null
        background = null
        super.onDetachedFromWindow()
    }

    private fun surface(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.dp.toFloat()
    }

    private fun updateScene() {
        val now = System.currentTimeMillis()
        val minute = (((now + TimeZone.getDefault().getOffset(now)) / 60_000L) % 1440).toInt()
        val hour = minute / 60
        if (clock.visibility == View.VISIBLE && minute != clockMinute) {
            if (clockMinute < 0) use24Hours = when (activity.prefs.clockFormat) {
                1 -> false
                2 -> true
                else -> DateFormat.is24HourFormat(activity)
            }
            clockMinute = minute
            val mm = (minute % 60).toString().padStart(2, '0')
            clock.text = if (use24Hours) "${hour.toString().padStart(2, '0')}:$mm" else
                "${(hour + 11) % 12 + 1}:$mm ${if (hour < 12) "AM" else "PM"}"
        }
        val day = when (activity.prefs.kidsScenery) {
            1 -> true
            2 -> false
            else -> hour in 6 until 18
        }
        if (day == dayScene) return
        dayScene = day
        val ink = if (day) NAVY else Color.WHITE
        title.setTextColor(ink)
        clock.setTextColor(ink)
        date.setTextColor(Style.withAlpha(ink, 210))
        val focusState = intArrayOf(android.R.attr.state_focused)
        exit.background = StateListDrawable().apply {
            addState(focusState, surface(GOLD, 24))
            addState(intArrayOf(), surface(if (day) 0xeef6f9ff.toInt() else 0xff282840.toInt(), 24))
        }
        val inks = android.content.res.ColorStateList(arrayOf(focusState, intArrayOf()), intArrayOf(NAVY, ink))
        exit.setTextColor(inks)
        exit.compoundDrawablesRelative[0]?.setTintList(inks)
        dock.background = surface(if (day) 0x4040749a else 0xb528294c.toInt(), 22)
        scenery.day = day
    }

    private companion object {
        val NAVY = 0xff111338.toInt()
        val GOLD = 0xffffc526.toInt()
    }

    private fun cellAt(index: Int) = if (useGrid) grid.cellAt(index) else row.cellAt(index)

    fun bind() {
        val allowed = activity.family.allowed
        val next = AppOrdering.sortForAllApps(activity.repo.apps.filter { it.packageName in allowed }, 0)
        if (entries == next) return
        val previous = entries?.getOrNull(selected)?.packageName
        val keepExit = exit.isFocused && !entries.isNullOrEmpty()
        entries = next
        val size = activity.prefs.iconSize
        // Keep the same banner size when a fourth app arrives. The tray wraps the
        // actual columns, leaving the remaining landscape visible beside short rows.
        val gap = 18.dp
        val glow = TileCell.GLOW_PAD.dp
        val available = resources.displayMetrics.widthPixels - 2 * activity.safeH
        val w = minOf(Style.favW(size), (available - 3 * gap - glow) / 4).coerceAtLeast(1)
        val h = w * 9 / 16
        grid.columns = if (next.size in 5..6) 3 else 4
        val visibleColumns = if (useGrid) minOf(grid.columns, next.size) else next.size
        val trayWidth = 2 * gap + glow + visibleColumns * w + (visibleColumns - 1).coerceAtLeast(0) * gap
        val params = dock.layoutParams as LinearLayout.LayoutParams
        params.width = minOf(trayWidth, available + 2 * gap)
        dock.layoutParams = params
        val cells = next.mapIndexed { index, app ->
            activity.makeCell(app, w, h, false).apply {
                tile.focusRingColor = GOLD
                isLongClickable = false
                setOnClickListener { activity.launch(app, tile) }
                focusListener = { _, hasFocus ->
                    if (hasFocus) {
                        selected = index
                        title.text = app.label
                    }
                }
            }
        }
        val target = next.indexOfFirst { it.packageName == previous }.coerceAtLeast(0)
        if (useGrid) grid.setCells(cells) else row.setCells(cells)
        selected = target
        title.text = next.getOrNull(target)?.label ?: "No apps selected"
        dock.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        val focusTarget = if (keepExit || next.isEmpty()) exit else cellAt(target)
        if (focusTarget != null && !focusTarget.requestFocus() && hasWindowFocus()) {
            focusTarget.requestFocusFromTouch()
        }
    }
}
