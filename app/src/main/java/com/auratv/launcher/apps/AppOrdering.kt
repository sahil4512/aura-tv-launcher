package com.auratv.launcher.apps

import java.util.Locale

/**
 * Pure list logic for favourites / hidden / sorting. No Android dependencies so it is unit tested.
 */
object AppOrdering {

    /** Launchers and vendor maintenance screens that have no place in the app catalog. */
    val NEVER_SHOW = setOf(
        "com.auratv.launcher", "com.amazon.tv.launcher",
        "com.tcl.rc.ota", "com.tcl.tcl_bt_rcu_service", "com.tcl.framework.custom",
        "com.tcl.initsetup", "com.tcl.useragreement",
    )

    /** Utility packages that are fine in All Apps but shouldn't be seeded into favourites. */
    private val NOT_FAVOURITE_BY_DEFAULT = setOf(
        "local.firetv.launcherhijack", "com.baronkiko.launcherhijack",
        "com.amazon.shoptv.firetv.client", "com.amazon.bueller.music",
    )

    /**
     * Sort hints for the very first launch only, so popular streaming apps come first.
     * This is *not* an app list — apps are always discovered from PackageManager.
     */
    private val SEED_PRIORITY = listOf(
        "com.netflix", "youtube", "com.amazon.firebat", "com.amazon.avod", "disney", "hotstar",
        "hulu", "hbo", "com.wbd", "primevideo", "appletv", "plex", "jellyfin", "kodi", "stremio",
        "spotify", "twitch", "downloader",
    )

    fun priority(pkg: String): Int {
        val i = SEED_PRIORITY.indexOfFirst { pkg.contains(it) }
        return if (i < 0) Int.MAX_VALUE else i
    }

    /** The "browsable" app set: hides system non-TV utilities unless the user opts in. */
    fun visible(all: List<AppEntry>, hidden: Set<String>, showSystem: Boolean): List<AppEntry> =
        all.filter { it.key !in hidden && (it.leanback || !it.system || showSystem) }

    fun seedFavorites(all: List<AppEntry>, max: Int = 10): List<String> =
        all.asSequence()
            .filter { it.leanback && it.packageName !in NOT_FAVOURITE_BY_DEFAULT }
            .sortedWith(compareBy<AppEntry>({ priority(it.packageName) }, { it.label.lowercase(Locale.ROOT) }))
            .take(max)
            .map { it.key }
            .toList()

    /**
     * Resolves saved favourite keys against installed apps. If an app changed its launcher activity
     * (common after updates) the favourite is migrated by package name instead of disappearing.
     * Returns the resolved entries; [migrated] is non-null when the saved key list needs updating.
     */
    data class Resolved(val entries: List<AppEntry>, val migrated: List<String>?)

    fun resolveFavorites(saved: List<String>, installed: List<AppEntry>, hidden: Set<String>): Resolved {
        val byKey = installed.associateBy { it.key }
        val byPkg = installed.groupBy { it.packageName }
        val out = ArrayList<AppEntry>(saved.size)
        val seen = HashSet<String>()
        var changed = false
        for (key in saved) {
            var e = byKey[key]
            if (e == null) {
                val pkg = key.substringBefore('/')
                e = byPkg[pkg]?.let { list -> list.firstOrNull { it.leanback } ?: list.first() }
                changed = true // either migrated or uninstalled
            }
            if (e == null || !seen.add(e.key)) continue
            if (e.key in hidden) continue
            out.add(e)
        }
        return Resolved(out, if (changed) out.map { it.key } else null)
    }

    /** New TV apps since the last scan get appended to favourites when auto-add is on. */
    fun newlyInstalled(known: Collection<String>, now: List<AppEntry>): List<AppEntry> {
        if (known.isEmpty()) return emptyList() // first run: nothing is "new"
        val k = known.toHashSet()
        return now.filter { it.key !in k && it.packageName !in k }
    }

    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices) return list
        val t = to.coerceIn(0, list.size - 1)
        if (t == from) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(t, item)
        return m
    }

    fun sortForAllApps(apps: List<AppEntry>, mode: Int): List<AppEntry> = when (mode) {
        1 -> apps.sortedByDescending { it.installedAt }
        else -> apps.sortedBy { it.label.lowercase(Locale.ROOT) }
    }
}
