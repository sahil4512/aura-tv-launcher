package com.auratv.launcher

import com.auratv.launcher.redirect.RedirectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectPolicyTest {
    private val self = "com.auratv.launcher"
    private val amazon = "com.amazon.tv.launcher"
    private val home = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private fun policy() = RedirectPolicy(self)

    @Test fun redirectsWhenAmazonHomeIsOnTop() {
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        val d = p.decide(1_300)
        assertTrue(d.redirect)
        assertEquals(1_000, d.homeSince)
    }

    @Test fun ignoresAmazonSettingsInsideTheLauncherPackage() {
        val p = policy()
        p.onForeground(amazon, "com.amazon.tv.launcher.ui.SettingsActivity", 1_000)
        assertFalse(p.decide(1_200).redirect)
    }

    @Test fun ignoresOtherAppsAndSelf() {
        val p = policy()
        p.onForeground("com.netflix.ninja", "x", 1_000)
        assertFalse(p.decide(1_100).redirect)
        p.onForeground(self, "x", 2_000)
        assertFalse(p.decide(2_100).redirect)
    }

    @Test fun doesNotSpamWhileSystemHoldsTheStart() {
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_300).redirect)
        // Polls during Fire OS's 5 s app-switch hold must not queue more starts.
        for (t in 1_600L..6_000L step 350) assertEquals("pending", p.decide(t).reason)
    }

    @Test fun retriesIfTheDeferredStartGotLost() {
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_300).redirect)
        assertTrue(p.decide(1_300 + RedirectPolicy.RETRY_AFTER_MS).redirect)
    }

    @Test fun nullClassCountsAsHome() {
        val p = policy()
        p.onForeground(amazon, null, 1_000)
        assertTrue(p.decide(1_100).redirect)
    }

    @Test fun doublePressHomeStaysOnAmazon() {
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_200).redirect)
        p.onForeground(self, "main", 6_000)          // Aura appears (after the 5 s hold)
        p.onForeground(amazon, home, 7_500)          // Home again within 3 s
        val d = p.decide(7_800)
        assertFalse(d.redirect)
        assertEquals("user-chose-amazon", d.reason)
        // Opening any other app clears the choice; the next Home redirects again.
        p.onForeground("com.netflix.ninja", "x", 20_000)
        p.onForeground(amazon, home, 30_000)
        assertTrue(p.decide(30_200).redirect)
    }

    @Test fun homePressedDuringTheHoldIsNotADoublePress() {
        // Measured on API 25: Home again during the 5 s hold releases our queued start, then Amazon
        // Home is shown on top of Aura 5 ms later. That must be redirected, not taken as "stay".
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_200).redirect)
        p.onForeground(self, "main", 2_500)
        p.onForeground(amazon, home, 2_505)
        val d = p.decide(2_700)
        assertTrue(d.redirect)
        // A real second press after Aura is back still keeps Amazon Home.
        p.onForeground(self, "main", 7_700)
        p.onForeground(amazon, home, 8_900)
        assertEquals("user-chose-amazon", p.decide(9_100).reason)
    }

    @Test fun slowSecondPressStillRedirects() {
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_200).redirect)
        p.onForeground(self, "main", 6_000)
        p.onForeground(amazon, home, 20_000)
        assertTrue(p.decide(20_300).redirect)
    }

    @Test fun doublePressCanBeDisabled() {
        val p = RedirectPolicy(self, bypassDoublePress = false)
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_200).redirect)
        p.onForeground(self, "main", 6_000)
        p.onForeground(amazon, home, 7_000)
        assertTrue(p.decide(7_200).redirect)
    }

    @Test fun loopGuardTripsAndPauses() {
        val p = RedirectPolicy(self, bypassDoublePress = false)
        var t = 0L
        var redirects = 0
        var tripped = false
        repeat(12) {
            t += 1_000
            p.onForeground(amazon, home, t)            // something keeps bouncing back to Amazon
            val d = p.decide(t + 50)
            if (d.redirect) redirects++
            if (d.reason == "loop-guard-tripped") tripped = true
            p.onForeground(self, "main", t + 400)
        }
        assertTrue(tripped)
        assertEquals(RedirectPolicy.LOOP_MAX_REDIRECTS, redirects)
        p.onForeground(amazon, home, t + 1_000)
        assertEquals("loop-guard-pause", p.decide(t + 1_100).reason)
        p.onForeground(amazon, home, t + RedirectPolicy.LOOP_PAUSE_MS + 2_000)
        assertTrue(p.decide(t + RedirectPolicy.LOOP_PAUSE_MS + 2_100).redirect)
    }

    @Test fun explicitAllowLastsUntilAnotherAppOpens() {
        val p = policy()
        p.allowAmazonOnce(500)
        p.onForeground(amazon, home, 1_000)
        assertFalse(p.decide(1_100).redirect)
        p.onForeground("com.netflix.ninja", "x", 5_000)
        p.onForeground(amazon, home, 9_000)
        assertTrue(p.decide(9_100).redirect)
    }

    @Test fun allowSurvivesFireOsSettingsTrampoline() {
        // Measured on the Fire TV: Settings intent → settings.v2 stub → Amazon Home (Settings tab).
        val p = policy()
        p.onForeground(self, "main", 1_000)
        p.allowAmazonOnce(2_000)
        p.onForeground("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.compliance.CTSDummySettingsHandler", 2_050)
        p.onForeground(amazon, home, 2_090)
        assertFalse(p.decide(2_200).redirect)
    }

    @Test fun allowWindowExpires() {
        val p = policy()
        p.allowAmazonOnce(1_000)
        p.onForeground(amazon, home, 1_000 + RedirectPolicy.ALLOW_WINDOW_MS + 1)
        assertTrue(p.decide(1_000 + RedirectPolicy.ALLOW_WINDOW_MS + 100).redirect)
    }

    // ---------- 0.4.0: Fire TV Settings stays usable ----------

    private val settingsV2 = "com.amazon.tv.settings.v2"

    @Test fun browsingFireTvSettingsNeverBouncesToAura() {
        // Aura → Fire TV Settings: Amazon Home's Settings tab, then Wi-Fi, Back, Display, Back…
        val p = policy()
        p.onForeground(self, "x", 0)
        p.allowAmazonOnce(1_000)
        p.onForeground(settingsV2, "$settingsV2.compliance.CTSDummySettingsHandler", 1_050)
        p.onForeground(amazon, home, 1_400)
        assertFalse(p.decide(1_500).redirect)
        p.onForeground(settingsV2, "$settingsV2.tv.network.WifiActivity", 5_000)
        assertFalse(p.decide(5_100).redirect)
        p.onForeground(amazon, home, 40_000) // Back, long after the 10 s allow window
        assertFalse(p.decide(40_100).redirect)
        p.onForeground(settingsV2, "$settingsV2.tv.display.DisplayActivity", 60_000)
        p.onForeground(amazon, home, 90_000)
        assertFalse(p.decide(90_100).redirect)
    }

    @Test fun settingsVisitEndsWhenAnotherAppOpens() {
        // Not permanent: as soon as a normal app is used, Home goes back to Aura.
        val p = policy()
        p.allowAmazonOnce(1_000)
        p.onForeground(amazon, home, 1_400)
        p.onForeground(settingsV2, "$settingsV2.tv.SettingsActivity", 3_000)
        p.onForeground("com.netflix.ninja", "x", 10_000)
        p.onForeground(amazon, home, 60_000)
        assertTrue(p.decide(60_100).redirect)
    }

    @Test fun settingsScreenWithoutAnExplicitVisitStillRedirectsAfterwards() {
        // e.g. a system dialog in the settings package from an app; Back lands on Amazon Home.
        val p = policy()
        p.onForeground("com.netflix.ninja", "x", 0)
        p.onForeground(settingsV2, "$settingsV2.tv.SettingsActivity", 1_000)
        p.onForeground(amazon, home, 2_000)
        assertTrue(p.decide(2_100).redirect)
    }

    @Test fun onlyRealSettingsPackagesCount() {
        val p = policy()
        assertTrue(p.isAmazonSettings("com.amazon.tv.settings"))
        assertTrue(p.isAmazonSettings(settingsV2))
        assertFalse(p.isAmazonSettings("com.amazon.avod.thirdpartyclient")) // Prime Video
        assertFalse(p.isAmazonSettings("com.amazon.venezia")) // Appstore
    }

    @Test fun primeVideoAndOtherAmazonAppsEndTheVisit() {
        val p = policy()
        p.allowAmazonOnce(1_000)
        p.onForeground(amazon, home, 1_200)
        p.onForeground("com.amazon.avod.thirdpartyclient", "x", 5_000)
        p.onForeground(amazon, home, 30_000)
        assertTrue(p.decide(30_100).redirect)
    }

    @Test fun rapidHomePressesQueueOneRedirectAndNeverTripTheLoopGuard() {
        // Five Home presses 400 ms apart while the system holds the first start (~5 s).
        val p = policy()
        p.onForeground("com.netflix.ninja", "x", 0)
        var redirects = 0
        for (i in 0 until 5) {
            val t = 1_000L + i * 400
            p.onForeground(amazon, home, t)
            if (p.decide(t + 50).redirect) redirects++
        }
        assertTrue(redirects <= 5)
        p.onForeground(self, "x", 6_000)
        assertFalse(p.decide(6_100).redirect)
        assertTrue(p.isPaused < 6_100)
    }

    @Test fun doublePressCountsAmazonHomesSlowStart() {
        // Fire TV Stick 2nd gen: Home pressed 2.4 s after GlowHome, Amazon Home's event lands 4.0 s after.
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_300).redirect)
        p.onForeground(self, "$self.ui.MainActivity", 6_000)
        p.onForeground(amazon, home, 10_040)
        assertFalse(p.decide(10_300).redirect)
    }

    @Test fun lateSecondPressIsRedirectedAgain() {
        val p = policy()
        p.onForeground(amazon, home, 1_000)
        assertTrue(p.decide(1_300).redirect)
        p.onForeground(self, "$self.ui.MainActivity", 6_000)
        p.onForeground(amazon, home, 11_000)
        assertTrue(p.decide(11_300).redirect)
    }
}
