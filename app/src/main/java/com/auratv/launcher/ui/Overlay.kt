package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/** Something drawn above the home screen that owns focus until closed (settings, menus, all apps). */
abstract class Overlay(val activity: MainActivity) {
    abstract val root: View
    /** Covers the whole wallpaper — the wallpaper motion is paused to save GPU while open. */
    open val coversWallpaper = false
    /** Return true if handled; false lets the activity close the overlay. */
    open fun onBack(): Boolean = false
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    abstract fun focusInitial()
    open fun onClosed() {}
    open fun onThemeChanged() {}
    fun close() = activity.closeOverlay(this)
    val ctx: Context get() = activity
}

/**
 * Standard list row for settings and menus: [thumb] title / subtitle ……… value.
 */
class MenuRow(context: Context) : LinearLayout(context) {
    val thumb = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val title = TextView(context).apply {
        sizeDp(17f); typeface = Style.REGULAR; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    val subtitle = TextView(context).apply {
        sizeDp(13f); typeface = Style.REGULAR; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    val value = TextView(context).apply { sizeDp(15f); typeface = Style.MEDIUM; maxLines = 1 }
    private val texts = LinearLayout(context).apply { orientation = VERTICAL }
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setPadding(16.dp, 11.dp, 16.dp, 11.dp)
        minimumHeight = 52.dp
        thumb.visibility = View.GONE
        addView(thumb, LayoutParams(96.dp, 54.dp).apply { rightMargin = 14.dp })
        texts.addView(title)
        texts.addView(subtitle)
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = 12.dp })
        thumb.clipToOutline = true
        thumb.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 6f.dp)
            }
        }
        refreshColors()
    }

    fun setThumb(b: Bitmap?, w: Int = 96, h: Int = 54) {
        thumb.visibility = View.VISIBLE
        thumb.layoutParams = (thumb.layoutParams as LayoutParams).apply { width = w.dp; height = h.dp }
        if (b != null) thumb.setImageBitmap(b) else thumb.setBackgroundColor(Style.theme.placeholder)
    }

    /** Label/value rows: the title keeps its natural width and a long value ellipsizes instead. */
    fun useInfoLayout() {
        texts.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        value.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 16.dp }
        value.gravity = Gravity.END
        value.ellipsize = TextUtils.TruncateAt.END
        value.typeface = Style.REGULAR
    }

    fun setSubtitle(s: CharSequence?) {
        subtitle.text = s
        subtitle.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun refreshColors() {
        val t = Style.theme
        background = Style.rowBackground(isFocused)
        title.setTextColor(if (isFocused) t.text else Style.withAlpha(t.text, 215))
        subtitle.setTextColor(t.textDim)
        value.setTextColor(if (isFocused) t.accent else t.textDim)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refreshColors()
        onFocus?.invoke(gainFocus)
    }
}

fun overlayTitle(ctx: Context, text: String, size: Float = 30f) = TextView(ctx).apply {
    this.text = text
    sizeDp(size)
    typeface = Style.LIGHT
    setTextColor(Style.theme.text)
}

/** Centered card with a message and choices. */
class ConfirmOverlay(
    activity: MainActivity,
    private val heading: String,
    private val message: String,
    private val choices: List<Pair<String, () -> Unit>>,
) : Overlay(activity) {

    private val rows = ArrayList<MenuRow>()

    override val root: View = FrameLayout(ctx).apply {
        setBackgroundColor(Style.withAlpha(Style.theme.shade, 150))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.panelBackground()
            setPadding(28.dp, 26.dp, 28.dp, 20.dp)
            addView(overlayTitle(ctx, heading, 24f))
            addView(TextView(ctx).apply {
                text = message; sizeDp(16f); setTextColor(Style.theme.textDim)
                setLineSpacing(0f, 1.15f)
                setPadding(0, 10.dp, 0, 16.dp)
            })
            for ((label, action) in choices) {
                val r = MenuRow(ctx)
                r.title.text = label
                r.setOnClickListener { close(); action() }
                rows.add(r)
                addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        addView(card, FrameLayout.LayoutParams(460.dp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun focusInitial() { rows.firstOrNull()?.requestFocus() }
}
