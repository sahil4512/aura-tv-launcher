package com.auratv.launcher

import android.view.KeyEvent
import com.auratv.launcher.redirect.CoverOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverOverlayTest {
    @Test fun backAndEscapeMeanStayOnAmazonHome() {
        assertEquals(CoverOverlay.KeyAction.STAY_ON_AMAZON, CoverOverlay.keyAction(KeyEvent.KEYCODE_BACK, kidsMode = false))
        assertEquals(CoverOverlay.KeyAction.STAY_ON_AMAZON, CoverOverlay.keyAction(KeyEvent.KEYCODE_ESCAPE, kidsMode = false))
    }

    @Test fun kidsModeHasNoWayOutFromTheCover() {
        assertEquals(CoverOverlay.KeyAction.CONSUME, CoverOverlay.keyAction(KeyEvent.KEYCODE_BACK, kidsMode = true))
    }

    @Test fun backAfterTheRequestCancelsThatRedirectOnly() {
        assertTrue(CoverOverlay.cancelsRedirect(cancelledAt = 10_500, requestedAt = 10_000))
        assertFalse(CoverOverlay.cancelsRedirect(cancelledAt = 9_000, requestedAt = 10_000)) // older Back
        assertFalse(CoverOverlay.cancelsRedirect(cancelledAt = 30_000, requestedAt = 10_000)) // stale
        assertFalse(CoverOverlay.cancelsRedirect(cancelledAt = 0, requestedAt = 10_000))
    }

    @Test fun otherKeysNeverReachTheHiddenAmazonScreen() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU))
            assertEquals(CoverOverlay.KeyAction.CONSUME, CoverOverlay.keyAction(k, kidsMode = false))
    }

    @Test fun coverOutlastsFireOsAppSwitchHold() {
        assertTrue(CoverOverlay.MAX_SHOW_MS > 5_000L)
    }
}
