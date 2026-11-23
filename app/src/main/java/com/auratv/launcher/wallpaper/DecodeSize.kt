package com.auratv.launcher.wallpaper

/**
 * How large to decode a wallpaper (pure, unit-tested).
 *
 * Wallpapers are soft, slowly moving artwork that the GPU scales to the screen anyway, and the
 * bundled art is 1920×1080. On a 4K display a screen-sized decode is 3840×2160 ARGB (≈33 MB, twice
 * that during a crossfade) for no visible gain, so the decode is capped at 1080p, keeping the
 * screen's aspect ratio. 1080p screens (Fire TV Stick 4K's UI) are unaffected.
 */
object DecodeSize {
    const val MAX_W = 1920
    const val MAX_H = 1080

    fun forScreen(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return MAX_W to MAX_H
        if (w <= MAX_W && h <= MAX_H) return w to h
        val s = minOf(MAX_W.toFloat() / w, MAX_H.toFloat() / h)
        return (w * s + 0.5f).toInt().coerceAtLeast(1) to (h * s + 0.5f).toInt().coerceAtLeast(1)
    }
}
