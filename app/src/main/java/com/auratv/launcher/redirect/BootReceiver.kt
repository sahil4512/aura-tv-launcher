package com.auratv.launcher.redirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.auratv.launcher.settings.Prefs

/**
 * Starts the redirect after boot / after Aura is updated — only if the user enabled it. Always
 * reconciles with the saved preferences: if the redirect is off, [HomeRedirect.apply] disables this
 * receiver again (e.g. the first update from 0.3.2, where it is still in its manifest-default state).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        HomeRedirect.apply(ctx, Prefs(ctx))
    }
}
