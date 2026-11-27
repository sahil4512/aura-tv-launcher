package com.auratv.launcher

import android.view.KeyEvent
import com.auratv.launcher.redirect.CoverOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverOverlayTest {
    @Test fun backAndEscapeRemoveTheCover() {
        assertEquals(CoverOverlay.KeyAction.HIDE, CoverOverlay.keyAction(KeyEvent.KEYCODE_BACK))
        assertEquals(CoverOverlay.KeyAction.HIDE, CoverOverlay.keyAction(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun otherKeysNeverReachTheHiddenAmazonScreen() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU))
            assertEquals(CoverOverlay.KeyAction.CONSUME, CoverOverlay.keyAction(k))
    }

    @Test fun coverOutlastsFireOsAppSwitchHold() {
        assertTrue(CoverOverlay.MAX_SHOW_MS > 5_000L)
    }
}
