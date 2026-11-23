package com.auratv.launcher

import android.view.KeyEvent
import com.auratv.launcher.apps.TileTone
import com.auratv.launcher.ui.FocusSpec
import com.auratv.launcher.ui.SettingsKeys
import com.auratv.launcher.wallpaper.DecodeSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unit tests for the UI/UX fix pass (see AURA_UI_UX_AUDIT.md and CHANGELOG.md). */
class UiFixesTest {

    // ---- A4: Settings must not swallow ◀ ▶ before focus search

    @Test fun settingsOnlyConsumesLeftOnTheSidebar() {
        assertTrue(SettingsKeys.consumeBeforeFocusSearch(KeyEvent.KEYCODE_DPAD_LEFT, sidebarFocused = true))
        assertFalse(SettingsKeys.consumeBeforeFocusSearch(KeyEvent.KEYCODE_DPAD_LEFT, sidebarFocused = false))
        assertFalse(SettingsKeys.consumeBeforeFocusSearch(KeyEvent.KEYCODE_DPAD_RIGHT, sidebarFocused = false))
        assertFalse(SettingsKeys.consumeBeforeFocusSearch(KeyEvent.KEYCODE_DPAD_RIGHT, sidebarFocused = true))
        for (k in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            assertFalse(SettingsKeys.consumeBeforeFocusSearch(k, true))
            assertFalse(SettingsKeys.consumeBeforeFocusSearch(k, false))
        }
    }

    // ---- C1: shared focus specification

    @Test fun focusRingNeverThinnerThanTheSofaMinimum() {
        for (themeRing in listOf(0f, 1f, 1.5f, 2f, 2.5f, 3f)) {
            assertTrue(FocusSpec.ringDp(themeRing) >= FocusSpec.MIN_RING_DP)
            assertTrue(FocusSpec.ringDp(themeRing) >= themeRing)
            assertEquals(FocusSpec.ringDp(themeRing) + 1f, FocusSpec.movingRingDp(themeRing), 0.0001f)
        }
    }

    @Test fun focusTimingAndScaleAreSane() {
        assertTrue(FocusSpec.IN_MS in 120..250)
        assertTrue(FocusSpec.OUT_MS in 100..FocusSpec.IN_MS)
        assertTrue(FocusSpec.SMALL_SCALE > 1.04f && FocusSpec.SMALL_SCALE < 1.12f)
    }

    @Test fun glowIsToneDownOnLightCardsOnly() {
        assertEquals(1f, FocusSpec.glowAlpha(lightCard = false), 0f)
        assertTrue(FocusSpec.glowAlpha(lightCard = true) < 1f)
    }

    @Test fun noBumpWhenAnimationsAreOff() {
        assertFalse(FocusSpec.bumpEnabled(0f))
        assertTrue(FocusSpec.bumpEnabled(0.6f))
        assertTrue(FocusSpec.bumpEnabled(1f))
    }

    /** Focus animations must go through Style.dur (scaled, and 0 when animations are off). */
    @Test fun noHardCodedFocusDurations() {
        val dir = File("src/main/java/com/auratv/launcher/ui")
        assertTrue("run from the app module", dir.isDirectory)
        val literal = Regex("""setDuration\(\s*\d""")
        val offenders = dir.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (literal.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }
        assertEquals("hard-coded focus animation durations", emptyList<String>(), offenders)
    }

    // ---- C2: card brightness balance

    @Test fun onlyNearWhiteBannersAreShaded() {
        assertEquals(0, TileTone.shadeAlpha(0f))
        assertEquals(0, TileTone.shadeAlpha(0.5f))
        assertEquals(0, TileTone.shadeAlpha(TileTone.WHITE_EDGE))
        assertEquals(TileTone.MAX_SHADE, TileTone.shadeAlpha(1f))
        val mid = TileTone.shadeAlpha(0.925f)
        assertTrue(mid in 1 until TileTone.MAX_SHADE)
        assertTrue(TileTone.MAX_SHADE <= 40) // a light shade: never distorts brand colours
    }

    @Test fun sheenOnlyOnNonWhiteCards() {
        assertTrue(TileTone.wantsSheen(0.3f))
        assertFalse(TileTone.wantsSheen(0.98f))
    }

    @Test fun lumaMatchesRec709() {
        assertEquals(1f, TileTone.luma(255, 255, 255), 0.001f)
        assertEquals(0f, TileTone.luma(0, 0, 0), 0.001f)
        assertEquals(0.7152f, TileTone.luma(0, 255, 0), 0.001f)
    }

    // ---- D1: wallpaper decode budget

    @Test fun decodeIsCappedAt1080pKeepingAspect() {
        assertEquals(1920 to 1080, DecodeSize.forScreen(1920, 1080))
        assertEquals(1280 to 720, DecodeSize.forScreen(1280, 720))
        assertEquals(1920 to 1080, DecodeSize.forScreen(3840, 2160))
        assertEquals(1920 to 1080, DecodeSize.forScreen(2560, 1440))
        val (w, h) = DecodeSize.forScreen(3840, 1600) // ultra-wide: width-limited
        assertEquals(1920, w)
        assertEquals(800, h)
        assertEquals(DecodeSize.MAX_W to DecodeSize.MAX_H, DecodeSize.forScreen(0, 0))
    }

    @Test fun decodeMemoryAt4kDropsFourfold() {
        val (w, h) = DecodeSize.forScreen(3840, 2160)
        val before = 3840L * 2160 * 4
        val after = w.toLong() * h * 4
        assertEquals(4.0, before.toDouble() / after, 0.01)
    }
}
