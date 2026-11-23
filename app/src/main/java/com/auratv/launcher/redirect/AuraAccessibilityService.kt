package com.auratv.launcher.redirect

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * EXPERIMENTAL "instant" Home button. Off unless the user both enables it in Aura's settings and
 * turns the accessibility service on (over ADB on Fire TV).
 *
 * If Fire OS delivers the Home key to accessibility key filtering, consuming it means Amazon Home
 * never starts — so Android's 5 s app-switch hold is never triggered and Aura opens immediately.
 * Fire OS 6.2+ is reported to handle Home before accessibility sees it; in that case this does
 * nothing harmful and window-change events still replace polling as the detection source.
 *
 * Trade-off when it does work: Fire TV's long-press-Home quick menu can't be reached while
 * instant mode is on (a consumed key can't be re-injected without system privileges). Holding
 * Home instead opens Amazon Home, where that menu's functions live.
 */
class AuraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        RedirectRuntime.accessibilityConnected = true
        Log.i(RedirectRuntime.TAG, "accessibility connected (instant mode ${RedirectRuntime.config.instantMode})")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (com.auratv.launcher.BuildConfig.DEBUG) Log.d(RedirectRuntime.TAG, "a11y key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}")
        val cfg = RedirectRuntime.config
        if (!cfg.enabled || !cfg.instantMode || event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) {
            val held = event.eventTime - event.downTime
            if (held >= LONG_PRESS_MS) {
                // Escape hatch: hold Home → Amazon Home (and it won't be redirected).
                Log.i(RedirectRuntime.TAG, "Home held ${held} ms: opening Amazon Home")
                RedirectRuntime.policy(this).allowAmazonOnce(System.currentTimeMillis())
                HomeRedirect.openAmazonHome(this)
            } else {
                Log.i(RedirectRuntime.TAG, "Home key intercepted by accessibility")
                RedirectRuntime.launchAura(this, "accessibility:key", System.currentTimeMillis() - (SystemClock.uptimeMillis() - event.downTime))
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Only subscribed to Amazon's launcher package (see aura_accessibility.xml). Instead of
        // deciding here, wake the usage-events poller now: one ordered source of truth, no polling delay.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) RedirectRuntime.pollNow?.invoke()
    }

    override fun onInterrupt() {}

    companion object {
        private const val LONG_PRESS_MS = 700L
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        RedirectRuntime.accessibilityConnected = false
        return super.onUnbind(intent)
    }
}
