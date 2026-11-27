package com.auratv.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All user preferences. Stored locally in a single SharedPreferences file.
 * No accounts, no telemetry, no network.
 */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Wallpaper
    var wallpaperType by str("wp_type", TYPE_SOLID)
    var wallpaperId by str("wp_id", DEFAULT_WALLPAPER)
    var videoPath by str("wp_video", "")
    var rotationMinutes by int("wp_rotate_min", 30)
    var transitionZoom by bool("wp_transition_zoom", true)
    var lastWallpaperChange by long("wp_last_change", 0L)
    var importedWallpapers by list("wp_imported")
    var dimPercent by int("dim", 20)

    // Motion
    var motionCinematic by bool("motion_cinematic", true)
    var motionIntensity by int("motion_intensity", 1)
    var motionSpeed by int("motion_speed", 1)
    var motion60fps by bool("motion_60fps", false)
    var parallax by bool("parallax", true)
    var uiAnimSpeed by int("ui_anim", 1)

    // Appearance
    var themeId by str("theme", "midnight")
    var accentColor by int("accent", 0)
    var showGreeting by bool("greeting", true)
    var showTagline by bool("tagline", true)
    var iconSize by int("icon_size", 1)
    var showLabels by bool("labels", false)
    var showClock by bool("clock", true)
    var clockFormat by int("clock_format", 0) // 0 system, 1 = 12h, 2 = 24h
    var showDate by bool("date", true)
    var showBrand by bool("brand", true)

    // Home & apps
    var rowPosition by int("row_pos", 1) // 0 centred, 1 lower third
    /** Home shows every app in a 4-column grid (true) or one favourites row plus Recently Used (false). */
    var homeGrid by bool("home_grid", true)
    /** Close other apps' background processes whenever Home is left for an app (see KeepLight). */
    var keepTvLight by bool("keep_light", true)

    // Screensaver (Android starts it after the idle time set in its own Screen saver settings).
    var screensaverSource by int("ss_source", 0)
    var screensaverFetchedAt by long("ss_fetched_at", 0L)
    var screensaverFetchedSource by int("ss_fetched_source", -1)
    var showAppsRow by bool("apps_row", false)
    var showRecentsRow by bool("recents_row", true)
    var launchHistory by list("launch_history")
    /** The app last opened from Aura: Home returns focus to its card (survives Aura being killed). */
    var lastLaunchedKey by str("last_launched", "")

    // Home button redirect (Fire OS). Off by default so it never competes with an existing helper.
    var redirectEnabled by bool("redirect_enabled", false)
    var buttonMappings by list("button_mappings")
    var redirectSpeed by int("redirect_speed", 1)
    var redirectDoublePress by bool("redirect_double_press", true)
    var redirectResident by bool("redirect_resident", true)
    var redirectInstant by bool("redirect_instant", false)
    var redirectLog by list("redirect_log")
    var showSystemApps by bool("system_apps", false)
    var autoAddNew by bool("auto_add", true)
    var allAppsSort by int("all_sort", 0) // 0 A–Z, 1 recently installed
    var favorites by list("favorites")
    var favoritesSeeded by bool("favorites_seeded", false)
    var hidden by list("hidden")
    var knownApps by list("known_apps")

    var videoWarningShown by bool("video_warning_shown", false)
    /** The first-start "Make GlowHome your home screen" prompt was answered (or dismissed) once. */
    var homePromptAnswered by bool("home_prompt_answered", false)

    fun hiddenSet(): Set<String> = hidden.toHashSet()

    // ---------- backup / restore ----------

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("format", "aura-tv-backup")
        root.put("version", 1)
        val values = JSONObject()
        for ((k, v) in sp.all) {
            if (k in TRANSIENT_KEYS) continue
            val typed = JSONObject()
            when (v) {
                is String -> typed.put("t", "s").put("v", v)
                is Int -> typed.put("t", "i").put("v", v)
                is Long -> typed.put("t", "l").put("v", v)
                is Boolean -> typed.put("t", "b").put("v", v)
                is Float -> typed.put("t", "f").put("v", v.toDouble())
                else -> continue
            }
            values.put(k, typed)
        }
        root.put("values", values)
        return root
    }

    /** Returns the number of values restored. Throws on a file that is not an Aura backup. */
    fun restoreJson(root: JSONObject): Int {
        require(root.optString("format") == "aura-tv-backup") { "Not a GlowHome TV backup file" }
        val values = root.getJSONObject("values")
        val e = sp.edit()
        e.clear()
        var n = 0
        for (k in values.keys()) {
            val o = values.getJSONObject(k)
            when (o.getString("t")) {
                "s" -> e.putString(k, o.getString("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                else -> continue
            }
            n++
        }
        e.apply()
        return n
    }

    fun resetAll() {
        sp.edit().clear().apply()
    }

    // ---------- delegates ----------

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Long) =
            sp.edit().putLong(key, value).apply()
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    /** Ordered string list stored newline-separated (keys never contain newlines). */
    private fun list(key: String) = object : ReadWriteProperty<Any, List<String>> {
        override fun getValue(thisRef: Any, property: KProperty<*>): List<String> =
            sp.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: List<String>) =
            sp.edit().putString(key, value.joinToString("\n")).apply()
    }

    companion object {
        const val FILE = "aura_prefs"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        /** Ambient glow in the focused app's colour (no wallpaper image at all). */
        const val TYPE_GLOW = "glow"
        /** One flat colour: nothing to decode, animate or redraw (the default since 0.6.2). */
        const val TYPE_SOLID = "solid"
        const val DEFAULT_WALLPAPER = "asset:lake.jpg"
        private val TRANSIENT_KEYS = setOf("known_apps", "wp_last_change", "redirect_log", "launch_history")

        val ROTATION_CHOICES = intArrayOf(0, 5, 15, 30, 60)
        val DIM_CHOICES = intArrayOf(0, 10, 20, 30, 40, 50, 60)
        val SPEED_FACTORS = floatArrayOf(0.6f, 1.0f, 1.6f)
        /** Multiplier for UI animation durations: Relaxed, Normal, Quick, Off. */
        val UI_ANIM_FACTORS = floatArrayOf(1.4f, 1.0f, 0.65f, 0f)
    }
}
