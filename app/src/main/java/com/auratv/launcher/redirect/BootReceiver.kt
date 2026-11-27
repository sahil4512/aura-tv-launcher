package com.auratv.launcher.redirect

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.ui.MainActivity

/** Restores the user's button settings on boot and update. Boot can also open the launcher. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        HomeRedirect.apply(ctx, prefs)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && prefs.openAfterRestart && HomeRedirect.isFireOs(ctx)) {
            val launch = Intent.makeMainActivity(ComponentName(ctx, MainActivity::class.java))
            try {
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            } catch (e: RuntimeException) {
                Log.w(RedirectRuntime.TAG, "Could not open GlowHome after restart", e)
            }
        }
    }
}
