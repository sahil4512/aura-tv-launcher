package com.auratv.launcher

import com.auratv.launcher.wallpaper.KenBurns
import com.auratv.launcher.wallpaper.WallpaperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KenBurnsTest {
    private val w = 1920
    private val h = 1080
    private val reserve = w * 0.012f

    @Test fun neverRevealsAnEdgeAtAnyIntensity() {
        val f = KenBurns.Frame()
        for (intensity in 0..2) {
            var t = 0.0
            while (t < 4000.0) { // > an hour of motion at normal speed
                KenBurns.compute(t, intensity, w, h, reserve, f)
                assertTrue("t=$t i=$intensity", KenBurns.covers(f, w, h, reserve))
                t += 0.37
            }
        }
    }

    @Test fun motionIsContinuous() {
        // At 60 fps the per-frame change must stay sub-pixel-ish: no jumps anywhere in the loop.
        val a = KenBurns.Frame(); val b = KenBurns.Frame()
        var t = 0.0
        var maxStep = 0f
        while (t < 2000.0) {
            KenBurns.compute(t, 2, w, h, reserve, a)
            KenBurns.compute(t + 1 / 60.0, 2, w, h, reserve, b)
            maxStep = maxOf(maxStep, abs(a.tx - b.tx), abs(a.ty - b.ty))
            t += 0.5
        }
        assertTrue("max step $maxStep px/frame", maxStep < 0.6f)
    }

    @Test fun actuallyMoves() {
        val a = KenBurns.compute(0.0, 1, w, h, reserve, KenBurns.Frame())
        val b = KenBurns.compute(20.0, 1, w, h, reserve, KenBurns.Frame())
        assertTrue(abs(a.tx - b.tx) > 5f || abs(a.scale - b.scale) > 0.005f)
    }

    @Test fun strongerIntensityZoomsMore() {
        var maxSubtle = 0f; var maxStrong = 0f
        val f = KenBurns.Frame()
        var t = 0.0
        while (t < 300) {
            maxSubtle = maxOf(maxSubtle, KenBurns.compute(t, 0, w, h, reserve, f).scale)
            maxStrong = maxOf(maxStrong, KenBurns.compute(t, 2, w, h, reserve, f).scale)
            t += 1.0
        }
        assertTrue(maxStrong > maxSubtle + 0.04f)
    }

    @Test fun sampleSizeDecodesNearScreenResolution() {
        assertEquals(1, WallpaperCatalog.sampleSize(1920, 1080, 1920, 1080))
        assertEquals(2, WallpaperCatalog.sampleSize(3840, 2160, 1920, 1080))
        assertEquals(2, WallpaperCatalog.sampleSize(6000, 4000, 1920, 1080)) // 3000x2000 still ≥ target
        assertEquals(1, WallpaperCatalog.sampleSize(1280, 720, 1920, 1080))
    }
}
