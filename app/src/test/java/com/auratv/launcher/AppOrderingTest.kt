package com.auratv.launcher

import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.AppOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOrderingTest {
    private fun app(pkg: String, cls: String = "$pkg.Main", label: String = pkg, lean: Boolean = true,
                    system: Boolean = false, installed: Long = 0) =
        AppEntry(pkg, cls, label, lean, system, installed, installed)

    private val netflix = app("com.netflix.ninja", label = "Netflix")
    private val youtube = app("com.amazon.firetv.youtube", label = "YouTube")
    private val prime = app("com.amazon.firebat", label = "Prime Video", system = true)
    private val downloader = app("com.esaba.downloader", label = "Downloader")
    private val hijack = app("local.firetv.launcherhijack", label = "Launcher Hijack")
    private val files = app("com.android.documentsui", label = "Files", lean = false, system = true)
    private val zebra = app("com.zebra", label = "Zebra")
    private val all = listOf(zebra, files, hijack, downloader, prime, youtube, netflix)

    @Test fun seedPutsStreamingFirstAndSkipsUtilities() {
        val seed = AppOrdering.seedFavorites(all)
        assertEquals(listOf(netflix.key, youtube.key, prime.key, downloader.key, zebra.key), seed)
    }

    @Test fun seedRespectsMax() {
        assertEquals(2, AppOrdering.seedFavorites(all, max = 2).size)
    }

    @Test fun visibleHidesSystemNonTvAppsUnlessAsked() {
        assertTrue(files !in AppOrdering.visible(all, emptySet(), showSystem = false))
        assertTrue(files in AppOrdering.visible(all, emptySet(), showSystem = true))
        assertTrue(prime in AppOrdering.visible(all, emptySet(), showSystem = false)) // system but TV app
        assertTrue(zebra !in AppOrdering.visible(all, setOf(zebra.key), showSystem = true))
    }

    @Test fun resolveKeepsOrderAndDropsUninstalled() {
        val r = AppOrdering.resolveFavorites(listOf(youtube.key, "gone.app/gone.Main", netflix.key), all, emptySet())
        assertEquals(listOf(youtube, netflix), r.entries)
        assertEquals(listOf(youtube.key, netflix.key), r.migrated)
    }

    @Test fun resolveMigratesChangedActivityByPackage() {
        val moved = app("com.netflix.ninja", cls = "com.netflix.ninja.NewMain", label = "Netflix")
        val r = AppOrdering.resolveFavorites(listOf(netflix.key), listOf(moved), emptySet())
        assertEquals(listOf(moved), r.entries)
        assertEquals(listOf(moved.key), r.migrated)
    }

    @Test fun resolveUnchangedHasNoMigration() {
        val r = AppOrdering.resolveFavorites(listOf(netflix.key, youtube.key), all, emptySet())
        assertNull(r.migrated)
    }

    @Test fun resolveSkipsHiddenAndDuplicates() {
        val r = AppOrdering.resolveFavorites(listOf(netflix.key, netflix.key, youtube.key), all, setOf(youtube.key))
        assertEquals(listOf(netflix), r.entries)
    }

    @Test fun moveWithinBounds() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), AppOrdering.move(l, 0, 1))
        assertEquals(listOf("a", "b", "d", "c"), AppOrdering.move(l, 3, 2))
        assertEquals(listOf("b", "c", "d", "a"), AppOrdering.move(l, 0, 99))
        assertEquals(l, AppOrdering.move(l, 9, 0))
    }

    @Test fun newlyInstalledIgnoresFirstRun() {
        assertTrue(AppOrdering.newlyInstalled(emptyList(), all).isEmpty())
        assertEquals(listOf(zebra), AppOrdering.newlyInstalled(all.filter { it != zebra }.map { it.key }, all))
    }

    @Test fun sortModes() {
        val a = app("x.a", label = "beta", installed = 5)
        val b = app("x.b", label = "Alpha", installed = 9)
        assertEquals(listOf(b, a), AppOrdering.sortForAllApps(listOf(a, b), 0))
        assertEquals(listOf(b, a), AppOrdering.sortForAllApps(listOf(a, b), 1))
    }

    @Test fun amazonHomeIsNeverShown() {
        assertTrue("com.amazon.tv.launcher" in AppOrdering.NEVER_SHOW)
        assertTrue("com.auratv.launcher" in AppOrdering.NEVER_SHOW)
    }

    @Test fun entrySerializationRoundTrips() {
        val e = app("p.q", label = "Tab\tName", installed = 1234)
        val back = AppEntry.parse(e.serialize())!!
        assertEquals(e.copy(label = "Tab Name"), back)
        assertNull(AppEntry.parse("garbage"))
    }
}

class RecentAppsTest {
    private fun app(pkg: String, lean: Boolean = true) =
        com.auratv.launcher.apps.AppEntry(pkg, "$pkg.Main", pkg, lean, false, 0, 0)

    @Test fun ranksByLastUsedAndExcludesLaunchers() {
        val apps = listOf(app("a"), app("b"), app("c"), app("com.spocky.projengmenu"), app("local.firetv.launcherhijack"))
        val used = mapOf("a" to 10L, "b" to 30L, "c" to 20L, "com.spocky.projengmenu" to 99L,
            "local.firetv.launcherhijack" to 98L, "not.installed" to 97L)
        val r = com.auratv.launcher.apps.RecentApps.rank(used, apps, exclude = setOf("com.spocky.projengmenu"), hidden = emptySet(), max = 10)
        assertEquals(listOf("b", "c", "a"), r.map { it.packageName })
    }

    @Test fun respectsHiddenAndMax() {
        val apps = listOf(app("a"), app("b"), app("c"))
        val used = mapOf("a" to 3L, "b" to 2L, "c" to 1L)
        val r = com.auratv.launcher.apps.RecentApps.rank(used, apps, emptySet(), hidden = setOf(apps[0].key), max = 1)
        assertEquals(listOf("b"), r.map { it.packageName })
    }

    @Test fun historyKeepsMostRecentFirstWithoutDuplicates() {
        val h = com.auratv.launcher.apps.RecentApps.recordLaunch(listOf("a", "b", "c"), "c")
        assertEquals(listOf("c", "a", "b"), h)
        val ranked = com.auratv.launcher.apps.RecentApps.fromHistory(h)
        assertTrue(ranked["c"]!! > ranked["a"]!! && ranked["a"]!! > ranked["b"]!!)
    }
}
