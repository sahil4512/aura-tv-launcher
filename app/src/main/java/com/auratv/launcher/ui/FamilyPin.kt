package com.auratv.launcher.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.settings.FamilySettings
import com.auratv.launcher.settings.PinVerifier
import com.auratv.launcher.util.Bg
import com.auratv.launcher.util.dp

/** Remote-friendly keypad. Verification runs only after a complete entry and off the UI thread. */
class FamilyPin(private val activity: MainActivity, private val family: FamilySettings) {
    private var dialog: AlertDialog? = null
    fun dismiss() { dialog?.dismiss() }
    fun verify(done: () -> Unit) = show(false, done)
    fun set(done: () -> Unit) = show(true, done)

    private fun show(setting: Boolean, done: () -> Unit) {
        if (dialog != null) return
        val pad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 4.dp, 16.dp, 8.dp)
        }
        val mask = TextView(activity).apply { gravity = Gravity.CENTER; textSize = 22f }
        pad.addView(mask)
        val prompt = AlertDialog.Builder(activity)
            .setTitle(if (setting) "Choose a 4-digit PIN" else "Enter PIN")
            .setView(pad).setNegativeButton("Cancel", null).create()
        dialog = prompt
        var digits = ""
        var first: String? = null
        var busy = false
        fun display() { mask.text = "●".repeat(digits.length) + "○".repeat(4 - digits.length) }
        fun input(key: String) {
            if (busy) return
            digits = if (key == "Delete") digits.dropLast(1) else digits + key
            display()
            if (digits.length != 4) return
            val entry = digits
            if (setting && first == null) {
                first = entry
                prompt.setTitle("Confirm PIN")
            } else if (setting && first != entry) {
                first = null
                prompt.setTitle("Choose a 4-digit PIN")
            } else {
                busy = true
                Bg.apps.execute {
                    val verifier = if (setting) PinVerifier.encode(entry) else null
                    val accepted = setting || family.matches(entry)
                    Bg.main.post {
                        if (dialog !== prompt || !prompt.isShowing) return@post
                        busy = false
                        if (accepted) {
                            verifier?.let(family::saveVerifier)
                            prompt.dismiss()
                            done()
                        } else display()
                    }
                }
            }
            digits = ""
            if (!busy) display()
        }
        for (keys in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("0", "Delete"))) {
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER }
            for (key in keys) row.addView(Button(activity).apply {
                text = key
                setOnClickListener { input(key) }
            }, LinearLayout.LayoutParams(84.dp, 44.dp))
            pad.addView(row)
        }
        display()
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
        (pad.getChildAt(1) as LinearLayout).getChildAt(0).requestFocus()
    }
}
