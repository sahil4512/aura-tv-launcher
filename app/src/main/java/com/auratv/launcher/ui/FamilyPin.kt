package com.auratv.launcher.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.settings.FamilySettings
import com.auratv.launcher.util.Bg
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp

class FamilyPin(private val activity: MainActivity, private val family: FamilySettings) {
    private var dialog: Dialog? = null
    fun dismiss() { dialog?.dismiss() }
    fun verify(done: () -> Unit) = show(false, done)
    fun set(done: () -> Unit) = show(true, done)

    private fun show(setting: Boolean, done: () -> Unit) {
        if (dialog != null) return
        val prompt = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        dialog = prompt
        val pad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Style.panelBackground(20f, opaque = true)
            setPadding(20.dp, 15.dp, 20.dp, 15.dp)
        }
        val title = TextView(activity).apply {
            text = if (setting) "Choose a 4-digit PIN" else "Enter PIN"
            sizeDp(24f)
            typeface = Style.MEDIUM
            setTextColor(Style.theme.text)
        }
        pad.addView(title)
        val mask = TextView(activity).apply {
            gravity = Gravity.CENTER
            sizeDp(17f)
            letterSpacing = .24f
            setTextColor(Style.accent)
        }
        pad.addView(mask, LinearLayout.LayoutParams(-1, 38.dp))
        var digits = ""
        var first: String? = null
        var busy = false
        fun display() {
            mask.text = "●".repeat(digits.length) + "○".repeat(4 - digits.length)
            mask.contentDescription = "${digits.length} of 4 digits entered"
        }
        fun input(key: String) {
            if (key == "Cancel") { prompt.dismiss(); return }
            if (busy) return
            digits = if (key == "Delete") digits.dropLast(1) else digits + key
            display()
            if (digits.length != 4) return
            val entry = digits
            digits = ""
            when {
                setting && first == null -> { first = entry; title.text = "Confirm PIN"; display() }
                setting && first != entry -> { first = null; title.text = "Choose a 4-digit PIN"; display() }
                else -> {
                    busy = true
                    title.text = if (setting) "Saving PIN…" else "Checking PIN…"
                    Bg.apps.execute {
                        val checked = try {
                            val encoded = if (setting) family.encodePin(entry) else null
                            (setting || family.matches(entry)) to encoded
                        } catch (_: Exception) { null }
                        Bg.main.post {
                            if (dialog !== prompt || !prompt.isShowing) return@post
                            busy = false
                            if (checked?.first == true) {
                                checked.second?.let(family::saveVerifier)
                                prompt.dismiss()
                                done()
                            } else {
                                if (setting) first = null
                                title.text = when {
                                    checked == null -> "PIN unavailable. Try again."
                                    setting -> "Choose a 4-digit PIN"
                                    else -> "Enter PIN"
                                }
                                display()
                            }
                        }
                    }
                }
            }
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Cancel", "0", "Delete")
        var initial: android.view.View? = null
        for (group in keys.chunked(3)) {
            val row = LinearLayout(activity)
            for (key in group) {
                val button = familyButton(activity, key, compact = key.length > 1)
                button.setOnClickListener { input(key) }
                if (key == "1") initial = button
                row.addView(button, LinearLayout.LayoutParams(84.dp, 42.dp).apply { setMargins(3.dp, 3.dp, 3.dp, 3.dp) })
            }
            pad.addView(row)
        }
        display()
        prompt.setContentView(pad)
        prompt.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        prompt.setOnDismissListener { if (dialog === prompt) dialog = null }
        prompt.setOnKeyListener { _, code, event ->
            val key = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (code - KeyEvent.KEYCODE_0).toString()
                KeyEvent.KEYCODE_DEL -> "Delete"
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_UP) input(key)
            true
        }
        prompt.show()
        prompt.window?.setLayout(320.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
        initial?.requestFocus()
    }
}
