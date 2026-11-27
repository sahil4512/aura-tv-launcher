package com.auratv.launcher.apps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * "Keep the TV light": after Home is left for an app, close the *background* processes of every
 * other app. Android only lets an ordinary app (with KILL_BACKGROUND_PROCESSES) end processes that
 * are in the background, including ones kept alive by a service (e.g. Netflix's NetflixService,
 * which doesn't restart once killed); the app on screen is never affected. System parts are left
 * alone. Measured on the TCL TV: Netflix's idle service held 141 MB and ~11 % of a 900 MHz core.
 */
object KeepLight {

    /** Packages never touched: Aura itself, the TV's own system parts, keyboards, Google services. */
    fun skip(pkg: String, self: String): Boolean =
        pkg == self || pkg == "android" ||
            NEVER_PREFIXES.any { pkg.startsWith(it) } || pkg in NEVER

    /** The packages to close, from the launchable apps: each once, minus the protected ones. */
    fun targets(apps: List<AppEntry>, self: String): List<String> =
        apps.asSequence().map { it.packageName }.distinct().filterNot { skip(it, self) }.toList()

    /** Kids Home keeps only its allowed apps in memory. Get names for cleanup after leaving Home. */
    fun targetsFromDevice(ctx: Context): List<String> {
        val packages = LinkedHashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (result in ctx.packageManager.queryIntentActivities(intent, 0)) {
                val info = result.activityInfo ?: continue
                val pkg = info.packageName
                if (info.enabled && info.applicationInfo.enabled && pkg !in AppOrdering.NEVER_SHOW && !skip(pkg, ctx.packageName))
                    packages.add(pkg)
            }
        }
        return packages.toList()
    }

    /** Off the UI thread: ends the background processes of [packages]; returns how many were asked. */
    fun closeBackground(ctx: Context, packages: List<String>): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        var n = 0
        for (p in packages) try { am.killBackgroundProcesses(p); n++ } catch (_: Exception) {}
        Log.i("AuraLight", "closed background processes of $n apps")
        return n
    }

    private val NEVER_PREFIXES = listOf("com.tcl.", "com.android.", "com.amazon.", "com.realtek.", "com.mediatek.")
    private val NEVER = setOf(
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.inputmethod.latin",
        "com.google.android.tv.remote.service", "com.google.android.katniss", "com.google.android.tvlauncher",
    )
}
