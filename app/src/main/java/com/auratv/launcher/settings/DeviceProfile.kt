package com.auratv.launcher.settings

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Tunes Aura to the TV it is installed on, once (first start of each install): slow or low-memory
 * TVs get the lightest setup. Measured basis: TCL RTD284x (4 × 900 MHz, Android low-RAM mode), where
 * a moving wallpaper alone cost ~26 % of a core and Netflix's idle service 141 MB.
 */
object DeviceProfile {

    data class Info(val lowRam: Boolean, val cores: Int, val maxMhz: Int, val totalMb: Long) {
        /** Slow or small: low-RAM mode, under 2 GB, or CPUs at 1.2 GHz or less. */
        val lowEnd get() = lowRam || totalMb in 1 until 2048 || maxMhz in 1..1200
        val label get() = "${if (lowEnd) "Light" else "Standard"} · $cores cores" +
            (if (maxMhz > 0) " at $maxMhz MHz" else "") + " · ${totalMb} MB" + (if (lowRam) " · low-RAM mode" else "")
    }

    fun detect(ctx: Context): Info {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = (0 until cores).maxOfOrNull { i ->
            try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() } catch (_: Exception) { 0 }
        } ?: 0
        return Info(am.isLowRamDevice, cores, maxKhz / 1000, mi.totalMem / (1024 * 1024))
    }

    /** First start of an install: pick defaults that suit this TV. Later changes by the user stay. */
    fun applyOnce(ctx: Context, p: Prefs) {
        val key = "profile_applied_v1"
        if (p.sp.getBoolean(key, false)) return
        val info = detect(ctx)
        if (info.lowEnd) {
            p.wallpaperType = Prefs.TYPE_SOLID // nothing to decode or redraw
            p.uiAnimSpeed = 2                  // Quick
        }
        p.sp.edit().putBoolean(key, true).putString("profile_label", info.label).apply()
        Log.i("Aura", "device profile: ${info.label}")
    }
}
