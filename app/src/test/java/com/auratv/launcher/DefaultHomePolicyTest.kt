package com.auratv.launcher

import com.auratv.launcher.redirect.DefaultHomePolicy
import com.auratv.launcher.redirect.DefaultHomePolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultHomePolicyTest {
    private fun route(home: Boolean = false, fire: Boolean = false, role: Boolean = false,
                      settings: Boolean = false, chooser: Boolean = false) =
        DefaultHomePolicy.route(home, fire, role, settings, chooser)

    @Test fun alreadyHomeWinsOverEverything() {
        assertEquals(Route.ALREADY_HOME, route(home = true, fire = true, role = true, settings = true, chooser = true))
    }

    @Test fun fireTvNeverOffersADefault() {
        assertEquals(Route.FIRE_TV, route(fire = true, role = true, settings = true, chooser = true))
    }

    @Test fun preferenceOrderIsRoleThenSettingsThenChooser() {
        assertEquals(Route.ROLE, route(role = true, settings = true, chooser = true))
        assertEquals(Route.HOME_SETTINGS, route(settings = true, chooser = true))
        assertEquals(Route.CHOOSER, route(chooser = true))
    }

    @Test fun stockAndroidTvWithAPriorityLauncherNeedsTheComputer() {
        // tvlauncher's priority-2 filter: Home resolves straight to it, no settings page, no role.
        assertEquals(Route.COMPUTER, route())
        assertNull(DefaultHomePolicy.prompt(Route.COMPUTER).goLabel)
        assertTrue(DefaultHomePolicy.prompt(Route.COMPUTER).message.contains("--set-home"))
        assertTrue(DefaultHomePolicy.prompt(Route.COMPUTER).message.contains("--replace-home"))
    }

    @Test fun chooserIsTheAndroidResolver() {
        assertTrue(DefaultHomePolicy.isChooser("android"))
        assertFalse(DefaultHomePolicy.isChooser("com.google.android.tvlauncher"))
        assertFalse(DefaultHomePolicy.isChooser(null))
    }

    @Test fun promptShowsOnceAndNeverWhenAlreadyHome() {
        assertTrue(DefaultHomePolicy.shouldPromptOnStart(answered = false, route = Route.ROLE))
        assertTrue(DefaultHomePolicy.shouldPromptOnStart(answered = false, route = Route.COMPUTER))
        assertFalse(DefaultHomePolicy.shouldPromptOnStart(answered = true, route = Route.ROLE))
        assertFalse(DefaultHomePolicy.shouldPromptOnStart(answered = false, route = Route.ALREADY_HOME))
    }

    @Test fun everyActionableRouteHasAButton() {
        for (r in listOf(Route.ROLE, Route.HOME_SETTINGS, Route.CHOOSER, Route.FIRE_TV))
            assertTrue(r.name, DefaultHomePolicy.prompt(r).goLabel != null)
    }
}
