package com.auratv.launcher.redirect

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.auratv.launcher.settings.Prefs

/** Facade used by the UI process: configuration, start/stop, and status checks. */
object HomeRedirect {
    const val EXTRA_SOURCE = "aura.redirect.source"
    const val EXTRA_DETECTED_AT = "aura.redirect.detected_at"
    const val EXTRA_REQUESTED_AT = "aura.redirect.requested_at"

    const val ACTION_APPLY = "com.auratv.launcher.redirect.APPLY"
    const val ACTION_STOP = "com.auratv.launcher.redirect.STOP"
    const val ACTION_ALLOW_AMAZON = "com.auratv.launcher.redirect.ALLOW_AMAZON"

    val HIJACK_PACKAGES = listOf("local.firetv.launcherhijack", "com.baronkiko.launcherhijack")

    /** Poll intervals (ms) while another app is in front; Aura in front uses the idle interval. */
    val SPEED_INTERVALS = longArrayOf(1000L, 500L, 300L)
    val SPEED_NAMES = listOf("Battery saver · 1 s", "Balanced · 0.5 s", "Fast · 0.3 s")

    data class Config(
        val enabled: Boolean = false,
        val activeIntervalMs: Long = 500L,
        val idleIntervalMs: Long = 1500L,
        val bypassDoublePress: Boolean = true,
        val resident: Boolean = true,
        val instantMode: Boolean = false,
        /** Debug builds only: treat another package as "Amazon Home" (emulator testing). */
        val homePackage: String = RedirectPolicy.AMAZON_LAUNCHER,
    ) {
        fun toIntent(i: Intent): Intent = i
            .putExtra("enabled", enabled).putExtra("active", activeIntervalMs).putExtra("idle", idleIntervalMs)
            .putExtra("bypass", bypassDoublePress).putExtra("resident", resident).putExtra("instant", instantMode)
            .putExtra("home", homePackage)

        companion object {
            fun from(p: Prefs) = Config(
                enabled = p.redirectEnabled,
                activeIntervalMs = SPEED_INTERVALS[p.redirectSpeed.coerceIn(0, 2)],
                idleIntervalMs = 1500L,
                bypassDoublePress = p.redirectDoublePress,
                resident = p.redirectResident,
                instantMode = p.redirectInstant,
                homePackage = testHome(p) ?: RedirectPolicy.AMAZON_LAUNCHER,
            )

            fun from(i: Intent): Config? = if (!i.hasExtra("enabled")) null else Config(
                i.getBooleanExtra("enabled", false), i.getLongExtra("active", 500L), i.getLongExtra("idle", 1500L),
                i.getBooleanExtra("bypass", true), i.getBooleanExtra("resident", true), i.getBooleanExtra("instant", false),
                i.getStringExtra("home") ?: RedirectPolicy.AMAZON_LAUNCHER,
            )
        }
    }

    /** Pushes the current preferences to the service (starting or stopping it). */
    fun apply(ctx: Context, p: Prefs) {
        val cfg = Config.from(p)
        val svc = Intent(ctx, HomeRedirectService::class.java)
        val run = cfg.enabled && isFireOs(ctx)
        setBootReceiverEnabled(ctx, run)
        if (run) {
            val i = cfg.toIntent(svc.setAction(ACTION_APPLY))
            try {
                if (Build.VERSION.SDK_INT >= 26 && cfg.resident) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {}
        } else {
            // stopService() is a no-op when nothing runs, so a disabled redirect never spawns its process.
            ctx.stopService(svc)
        }
    }

    /**
     * BootReceiver runs in ":redirect", so while the redirect is off it is disabled: boot and app
     * updates then don't spawn that process just to read one preference. The state persists.
     */
    private fun setBootReceiverEnabled(ctx: Context, enabled: Boolean) {
        val pm = ctx.packageManager
        val cn = ComponentName(ctx, BootReceiver::class.java)
        try {
            bootReceiverStateChange(pm.getComponentEnabledSetting(cn), enabled)?.let {
                pm.setComponentEnabledSetting(cn, it, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) {}
    }

    /** New component state for BootReceiver, or null when [current] already matches. Pure (unit tested). */
    fun bootReceiverStateChange(current: Int, enabled: Boolean): Int? {
        // The manifest default (DEFAULT) is enabled.
        val isOn = current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        return when {
            enabled && !isOn -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            !enabled && isOn -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else -> null
        }
    }

    /** Opens Amazon Home on purpose, telling the service not to redirect it. */
    fun openAmazonHome(ctx: Context): Boolean {
        // RedirectRuntime is per-process: in the UI process serviceActive is always false, so also
        // check the saved setting — a disabled redirect must never spawn the ":redirect" process.
        if (!RedirectRuntime.serviceActive && Prefs(ctx).redirectEnabled && isFireOs(ctx)) try { ctx.startService(Intent(ctx, HomeRedirectService::class.java).setAction(ACTION_ALLOW_AMAZON)) } catch (_: Exception) {}
        return try {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(testHome(Prefs(ctx)) ?: RedirectPolicy.AMAZON_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { false }
    }

    fun testHome(p: Prefs): String? =
        if (com.auratv.launcher.BuildConfig.DEBUG) p.sp.getString("redirect_test_home", null)?.takeIf { it.isNotEmpty() } else null

    fun isFireOs(ctx: Context): Boolean = testHome(Prefs(ctx)) != null || try {
        ctx.packageManager.getPackageInfo("com.amazon.tv.launcher", 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT)
            ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        else mode == AppOpsManager.MODE_ALLOWED
    }

    fun installedHijack(ctx: Context): String? = HIJACK_PACKAGES.firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
    }

    fun accessibilityEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, AuraAccessibilityService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun usageAccessCommand(ctx: Context) = "adb shell appops set ${ctx.packageName} GET_USAGE_STATS allow"
}
