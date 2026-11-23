package com.auratv.launcher

import android.content.pm.PackageManager
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.TileFiles
import com.auratv.launcher.redirect.HomeRedirect
import com.auratv.launcher.redirect.UsageAccessCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceFixesTest {

    // ---------- tile disk cache ----------

    private fun entry(pkg: String = "com.netflix.ninja", updatedAt: Long = 100L) =
        AppEntry(pkg, "$pkg.Main", "App", leanback = true, system = false, installedAt = 1L, updatedAt = updatedAt)

    @Test fun otherSizesOfTheSameGenerationAreKept() {
        val e = entry()
        val gen = TileFiles.generation(e.updatedAt, 4)
        val fav = TileFiles.name(e, 300, 225, 4)
        val recent = TileFiles.name(e, 276, 193, 4)
        assertTrue(fav != recent)
        assertFalse(TileFiles.isStale(fav, e.packageName, gen))
        assertFalse(TileFiles.isStale(recent, e.packageName, gen))
    }

    @Test fun olderVersionsStylesAndOldNamesAreStale() {
        val gen = TileFiles.generation(200L, 4)
        assertTrue(TileFiles.isStale(TileFiles.name(entry(updatedAt = 100L), 300, 225, 4), "com.netflix.ninja", gen))
        assertTrue(TileFiles.isStale(TileFiles.name(entry(updatedAt = 200L), 300, 225, 3), "com.netflix.ninja", gen))
        assertTrue(TileFiles.isStale("com.netflix.ninja~1a2b3c.png", "com.netflix.ninja", gen)) // 0.3.2 naming
    }

    @Test fun otherPackagesAreNeverTouched() {
        val gen = TileFiles.generation(100L, 4)
        // "com.netflix.ninja2" shares a prefix with "com.netflix.ninja" but is a different package.
        assertFalse(TileFiles.isStale(TileFiles.name(entry("com.netflix.ninja2", 1L), 300, 225, 4), "com.netflix.ninja", gen))
        assertFalse(TileFiles.isStale("com.youtube~1v4~300x225~ab.png", "com.netflix.ninja", gen))
    }

    @Test fun pruneRemovesUninstalledAppsAndOldGenerationsOnly() {
        val nf = entry("com.netflix.ninja", 200L)
        val yt = entry("com.google.youtube", 50L)
        val live = mapOf(nf.packageName to TileFiles.generation(200L, 4), yt.packageName to TileFiles.generation(50L, 4))
        val keepFav = TileFiles.name(nf, 300, 225, 4)
        val keepRecent = TileFiles.name(nf, 276, 193, 4)
        val keepYt = TileFiles.name(yt, 300, 225, 4)
        val oldVersion = TileFiles.name(entry("com.netflix.ninja", 100L), 300, 225, 4)
        val oldStyle = TileFiles.name(nf, 300, 225, 3)
        val uninstalled = TileFiles.name(entry("com.gone.app", 1L), 300, 225, 4)
        val legacy = "com.google.youtube~1a2b3c.png"
        val junk = "partial.tmp"
        val out = TileFiles.prunable(listOf(keepFav, keepRecent, keepYt, oldVersion, oldStyle, uninstalled, legacy, junk), live)
        assertEquals(setOf(oldVersion, oldStyle, uninstalled, legacy, junk), out.toSet())
    }

    @Test fun diskBudgetDeletesOldestFirstAndOnlyWhenOver() {
        val files = listOf(TileFiles.Info("a", 10, 300), TileFiles.Info("b", 10, 100), TileFiles.Info("c", 10, 200))
        assertTrue(TileFiles.overBudget(files, 30).isEmpty())
        assertEquals(listOf("b"), TileFiles.overBudget(files, 25))
        assertEquals(listOf("b", "c"), TileFiles.overBudget(files, 10))
    }

    // ---------- usage-access cache ----------

    @Test fun grantedAccessIsCachedUntilTheTtlExpires() {
        val c = UsageAccessCache(grantedTtlMs = 60_000)
        var calls = 0
        val check = { calls++; true }
        assertTrue(c.get(1_000, check))
        assertTrue(c.get(30_000, check))
        assertTrue(c.get(60_999, check))
        assertEquals(1, calls)
        assertTrue(c.get(61_000, check))
        assertEquals(2, calls)
    }

    @Test fun grantRevokeRestoreAreAllNoticed() {
        val c = UsageAccessCache(grantedTtlMs = 60_000, deniedTtlMs = 9_000)
        var granted = false
        var calls = 0
        val check = { calls++; granted }
        assertFalse(c.get(0, check))              // not granted yet
        granted = true
        assertTrue(c.get(10_000, check))          // grant noticed on the next 10 s retry
        granted = false
        assertTrue(c.get(20_000, check))          // revoked: still cached as granted…
        assertFalse(c.get(70_000, check))         // …until the grant TTL expires
        granted = true
        assertTrue(c.get(80_000, check))          // restored: noticed on the next 10 s retry
        assertEquals(4, calls)
    }

    @Test fun deniedAccessIsNotRecheckedOnBurstsOfPolls() {
        val c = UsageAccessCache(grantedTtlMs = 60_000, deniedTtlMs = 9_000)
        var calls = 0
        val check = { calls++; false }
        // Accessibility window events / screen-on trigger extra polls while access is denied.
        for (t in longArrayOf(0, 150, 900, 2_000, 5_000, 8_999)) assertFalse(c.get(t, check))
        assertEquals(1, calls)
        assertFalse(c.get(10_000, check))         // the scheduled 10 s retry always re-checks
        assertEquals(2, calls)
    }

    @Test fun aNewCacheAlwaysChecks() {
        // A restarted redirect service builds a new cache: the first poll must ask AppOps, even at time 0.
        var calls = 0
        assertTrue(UsageAccessCache().get(0) { calls++; true })
        assertEquals(1, calls)
    }

    @Test fun clockGoingBackwardsForcesARecheck() {
        val c = UsageAccessCache(grantedTtlMs = 60_000)
        var calls = 0
        c.get(50_000) { calls++; true }
        c.get(10_000) { calls++; true }
        assertEquals(2, calls)
    }

    // ---------- BootReceiver component state ----------

    @Test fun bootReceiverDisabledWhileRedirectIsOff() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            HomeRedirect.bootReceiverStateChange(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, enabled = false))
        assertNull(HomeRedirect.bootReceiverStateChange(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, enabled = false))
    }

    @Test fun bootReceiverReEnabledWhenRedirectTurnsOn() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            HomeRedirect.bootReceiverStateChange(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, enabled = true))
        assertNull(HomeRedirect.bootReceiverStateChange(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, enabled = true))
        assertNull(HomeRedirect.bootReceiverStateChange(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, enabled = true))
    }
}
