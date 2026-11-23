package com.auratv.launcher.settings

import android.graphics.Color

/**
 * Aura's visual themes. A theme only changes colours and a few proportions — never layout —
 * so switching is instant and cheap.
 */
class AuraTheme(
    val id: String,
    val name: String,
    val accent: Int,
    val text: Int,
    val textDim: Int,
    /** Overlay panels (settings, menus, all-apps backdrop). */
    val panel: Int,
    val panelStroke: Int,
    /** Colour of the wallpaper scrim gradients. */
    val shade: Int,
    val topShade: Float,
    val bottomShade: Float,
    val ring: Int,
    val ringWidthDp: Float,
    val cornerDp: Float,
    val focusScale: Float,
    val placeholder: Int,
) {
    companion object {
        val MIDNIGHT = AuraTheme(
            "midnight", "Midnight",
            accent = Color.rgb(143, 184, 255), text = Color.rgb(242, 245, 250), textDim = Color.argb(160, 220, 228, 245),
            panel = Color.argb(236, 12, 17, 30), panelStroke = Color.argb(40, 160, 190, 255),
            shade = Color.rgb(4, 8, 20), topShade = 0.55f, bottomShade = 0.85f,
            ring = Color.WHITE, ringWidthDp = 2.5f, cornerDp = 12f, focusScale = 1.10f,
            placeholder = Color.rgb(26, 34, 56),
        )
        val MINIMAL = AuraTheme(
            "minimal", "Minimal Dark",
            accent = Color.WHITE, text = Color.WHITE, textDim = Color.argb(150, 255, 255, 255),
            panel = Color.argb(242, 12, 12, 12), panelStroke = Color.argb(30, 255, 255, 255),
            shade = Color.BLACK, topShade = 0.5f, bottomShade = 0.8f,
            ring = Color.WHITE, ringWidthDp = 2.5f, cornerDp = 8f, focusScale = 1.08f,
            placeholder = Color.rgb(34, 34, 34),
        )
        val GLASS = AuraTheme(
            "glass", "Soft Glass",
            accent = Color.rgb(232, 240, 255), text = Color.WHITE, textDim = Color.argb(175, 240, 244, 255),
            panel = Color.argb(200, 38, 44, 58), panelStroke = Color.argb(70, 255, 255, 255),
            shade = Color.rgb(16, 20, 30), topShade = 0.35f, bottomShade = 0.6f,
            ring = Color.argb(200, 255, 255, 255), ringWidthDp = 1.5f, cornerDp = 16f, focusScale = 1.12f,
            placeholder = Color.argb(150, 70, 80, 100),
        )
        val CINEMATIC = AuraTheme(
            "cinematic", "Cinematic",
            accent = Color.rgb(245, 196, 107), text = Color.rgb(250, 247, 240), textDim = Color.argb(160, 240, 232, 215),
            panel = Color.argb(240, 10, 9, 8), panelStroke = Color.argb(50, 245, 196, 107),
            shade = Color.BLACK, topShade = 0.7f, bottomShade = 0.95f,
            ring = Color.rgb(245, 196, 107), ringWidthDp = 2f, cornerDp = 8f, focusScale = 1.10f,
            placeholder = Color.rgb(30, 26, 20),
        )
        val WARM = AuraTheme(
            "warm", "Warm Ambient",
            accent = Color.rgb(255, 179, 138), text = Color.rgb(255, 246, 238), textDim = Color.argb(165, 255, 228, 210),
            panel = Color.argb(236, 30, 18, 12), panelStroke = Color.argb(50, 255, 179, 138),
            shade = Color.rgb(26, 12, 6), topShade = 0.5f, bottomShade = 0.82f,
            ring = Color.rgb(255, 214, 190), ringWidthDp = 2f, cornerDp = 14f, focusScale = 1.10f,
            placeholder = Color.rgb(60, 36, 26),
        )

        val ALL = listOf(MIDNIGHT, MINIMAL, GLASS, CINEMATIC, WARM)

        fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: MIDNIGHT
    }
}
