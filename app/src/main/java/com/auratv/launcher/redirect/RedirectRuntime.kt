package com.auratv.launcher.redirect

import android.content.Context
import android.content.Intent
import android.util.Log
import com.auratv.launcher.ui.MainActivity

/**
 * State shared by everything in the lightweight ":redirect" process (the service and the optional
 * accessibility service). The UI process never touches this object.
 */
object RedirectRuntime {
    @Volatile var config = HomeRedirect.Config()
    @Volatile var serviceActive = false
    @Volatile var accessibilityConnected = false
    var policy: RedirectPolicy? = null
    /** Set by the service: triggers an immediate poll (used by accessibility window events). */
    @Volatile var pollNow: (() -> Unit)? = null

    fun policy(ctx: Context): RedirectPolicy =
        policy ?: RedirectPolicy(ctx.packageName, config.bypassDoublePress).also { policy = it }

    /** Brings Aura to the front. [detectedAt] = wall-clock time Amazon Home came up (or Home was pressed). */
    fun launchAura(ctx: Context, source: String, detectedAt: Long) {
        val requestedAt = System.currentTimeMillis()
        val i = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(HomeRedirect.EXTRA_SOURCE, source)
            .putExtra(HomeRedirect.EXTRA_DETECTED_AT, detectedAt)
            .putExtra(HomeRedirect.EXTRA_REQUESTED_AT, requestedAt)
        try {
            ctx.startActivity(i)
            Log.i(TAG, "redirect ($source): requested ${requestedAt - detectedAt} ms after Amazon Home")
        } catch (e: Exception) {
            Log.w(TAG, "redirect failed", e)
        }
    }

    const val TAG = "AuraRedirect"
}
