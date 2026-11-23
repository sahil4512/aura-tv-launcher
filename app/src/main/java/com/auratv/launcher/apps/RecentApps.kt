package com.auratv.launcher.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import com.auratv.launcher.redirect.HomeRedirect

/**
 * "Recently used" row. Uses the system's usage history when Aura has usage access (the same
 * one-time grant the Home redirect needs), otherwise Aura's own launch history.
 */
object RecentApps {

    /** Launchers and helpers never belong in a recents row. */
    private val ALWAYS_EXCLUDED = AppOrdering.NEVER_SHOW + HomeRedirect.HIJACK_PACKAGES

    fun lastUsedFromSystem(ctx: Context, days: Int = 14): Map<String, Long> {
        if (!HomeRedirect.hasUsageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val now = System.currentTimeMillis()
        val out = HashMap<String, Long>()
        try {
            for (s in usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - days * 86_400_000L, now)) {
                if (s.totalTimeInForeground < 3_000) continue // ignore pass-through flashes
                val prev = out[s.packageName] ?: 0L
                if (s.lastTimeUsed > prev) out[s.packageName] = s.lastTimeUsed
            }
        } catch (_: Exception) {}
        return out
    }

    /** Real launchers (Projectivy, Amazon Home…). FallbackHome stubs inside Settings apps use a negative priority. */
    fun homeApps(ctx: Context): Set<String> = try {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.priority >= 0 }
            .mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    /** History list (most recent first) → map with synthetic, ordered timestamps. */
    fun fromHistory(history: List<String>): Map<String, Long> =
        history.mapIndexed { i, pkg -> pkg to (history.size - i).toLong() }.toMap()

    /** Pure ranking (unit tested). */
    fun rank(lastUsed: Map<String, Long>, apps: List<AppEntry>, exclude: Set<String>, hidden: Set<String>, max: Int): List<AppEntry> {
        val byPkg = HashMap<String, AppEntry>()
        for (a in apps) {
            val cur = byPkg[a.packageName]
            if (cur == null || (a.leanback && !cur.leanback)) byPkg[a.packageName] = a
        }
        return lastUsed.entries
            .asSequence()
            .filter { it.key !in exclude && it.key !in ALWAYS_EXCLUDED }
            .sortedByDescending { it.value }
            .mapNotNull { byPkg[it.key] }
            .filter { it.key !in hidden }
            .take(max)
            .toList()
    }

    fun recordLaunch(history: List<String>, pkg: String, max: Int = 16): List<String> =
        (listOf(pkg) + history.filter { it != pkg }).take(max)
}
