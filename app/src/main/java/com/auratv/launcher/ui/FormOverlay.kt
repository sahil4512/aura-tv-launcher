package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.util.Dp
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

/**
 * Centered card like [ConfirmOverlay] with text fields (and an optional switch) above the
 * choices: used for a network share's address and login. Select on a field opens the TV's
 * keyboard; ▼ moves on to the next row.
 */
class FormOverlay(
    activity: MainActivity,
    private val heading: String,
    private val message: String?,
    private val fields: List<Field>,
    private val toggle: Toggle?,
    private val choices: List<Pair<String, (Result) -> Unit>>,
) : Overlay(activity) {

    class Field(val label: String, val initial: String = "", val password: Boolean = false, val hint: String? = null)
    class Toggle(val label: String, val initial: Boolean)
    /** What was entered: one value per field, and the switch. */
    class Result(val values: List<String>, val toggled: Boolean)

    private val edits = ArrayList<EditText>()
    private var toggled = toggle?.initial ?: false

    private fun result() = Result(edits.map { it.text.toString().trim() }, toggled)

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    override fun onClosed() = hideKeyboard()

    override val root: View = FrameLayout(ctx).apply {
        setBackgroundColor(Style.withAlpha(Style.theme.shade, 150))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.panelBackground()
            setPadding(28.dp, 26.dp, 28.dp, 20.dp)
            addView(overlayTitle(ctx, heading, 24f))
            if (!message.isNullOrEmpty()) addView(TextView(ctx).apply {
                text = message; sizeDp(16f); setTextColor(Style.theme.textDim)
                setLineSpacing(0f, 1.15f)
                setPadding(0, 10.dp, 0, 4.dp)
            })
            fields.forEachIndexed { i, f ->
                addView(TextView(ctx).apply {
                    text = f.label; sizeDp(13f); typeface = Style.MEDIUM
                    setTextColor(Style.withAlpha(Style.theme.text, 170))
                    setPadding(4.dp, 12.dp, 0, 6.dp)
                })
                val e = ThemedEdit(ctx).apply {
                    setText(f.initial)
                    hint = f.hint
                    inputType = if (f.password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = (if (i < fields.size - 1) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE) or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
                edits.add(e)
                addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50.dp))
            }
            if (toggle != null) {
                addView(MenuRow(ctx).apply {
                    title.text = toggle.label
                    value.visibility = View.GONE
                    val sw = SettingsPanel.ToggleView(ctx).apply { checked = toggled }
                    addView(sw, LinearLayout.LayoutParams(44.dp, 24.dp).apply { leftMargin = 14.dp })
                    setOnClickListener { toggled = !toggled; sw.checked = toggled }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp })
            }
            addView(View(ctx), LinearLayout.LayoutParams(1, 8.dp))
            for ((label, action) in choices) {
                val r = MenuRow(ctx)
                r.title.text = label
                r.setOnClickListener { val res = result(); hideKeyboard(); close(); action(res) }
                addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        addView(card, FrameLayout.LayoutParams(500.dp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** First empty field, else the first choice. */
    override fun focusInitial() {
        (edits.firstOrNull { it.text.isEmpty() } ?: edits.firstOrNull())?.requestFocus()
    }

    /**
     * Text field in the panel's style: a quiet outline, the accent highlight when focused.
     * The keyboard opens on Select (not on focus), so ▲▼ can move past the field.
     */
    private class ThemedEdit(c: Context) : EditText(c) {
        init {
            sizeDp(17f)
            typeface = Style.REGULAR
            setSingleLine(true)
            setTextColor(Style.theme.text)
            setHintTextColor(Style.withAlpha(Style.theme.textDim, 160))
            setPadding(14.dp, 0, 14.dp, 0)
            gravity = Gravity.CENTER_VERTICAL
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            showSoftInputOnFocus = false
            highlightColor = Style.withAlpha(Style.accent, 90)
            refresh()
            setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                    showKeyboard(); true
                } else false
            }
            setOnClickListener { showKeyboard() }
        }

        private fun showKeyboard() {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }

        private fun refresh() {
            background = if (isFocused) Style.focusHighlight() else GradientDrawable().apply {
                setColor(Style.withAlpha(Style.theme.text, 14))
                cornerRadius = 10f * Dp.density
                setStroke(1.dp, Style.withAlpha(Style.theme.text, 40))
            }
        }

        override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect)
            refresh()
        }
    }
}
