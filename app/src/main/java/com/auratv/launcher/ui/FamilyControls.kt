package com.auratv.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

internal fun familySurface(fill: Int, edge: Int, radius: Int) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius.dp.toFloat()
    if (edge != Color.TRANSPARENT) setStroke(2.dp, edge)
}

internal fun familyButton(ctx: Context, label: String, compact: Boolean = false) = Button(ctx).apply {
    val theme = Style.theme
    val focus = Style.accent
    val brightness = Color.red(focus) * 299 + Color.green(focus) * 587 + Color.blue(focus) * 114
    val focusText = if (brightness >= 145000) Color.rgb(15, 19, 30) else Color.WHITE
    text = label
    isAllCaps = false
    gravity = Gravity.CENTER
    typeface = Style.MEDIUM
    sizeDp(if (compact) 16f else 23f)
    setPadding(8.dp, 0, 8.dp, 0)
    minWidth = 0
    minHeight = 0
    stateListAnimator = null
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(focusText, focusText, theme.text)))
    backgroundTintList = null
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), familySurface(focus, theme.text, 12))
        addState(intArrayOf(android.R.attr.state_pressed), familySurface(focus, theme.text, 12))
        addState(intArrayOf(), familySurface(theme.placeholder, theme.panelStroke, 12))
    }
}
