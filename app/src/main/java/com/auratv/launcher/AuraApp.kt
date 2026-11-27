package com.auratv.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import com.auratv.launcher.apps.AppRepository
import com.auratv.launcher.apps.TileCache
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.util.Dp

class AuraApp : Application() {
    lateinit var prefs: Prefs
    lateinit var repo: AppRepository
    lateinit var tiles: TileCache

    /** True in the lightweight ":redirect" process, which must stay tiny: no app scan, no caches. */
    var isRedirectProcess = false
        private set

    override fun onCreate() {
        super.onCreate()
        Dp.init(this)
        prefs = Prefs(this)
        isRedirectProcess = currentProcessName().endsWith(":redirect")
        if (isRedirectProcess) return
        // Home reads kids-mode state in onCreate; start loading that small file now, off the main
        // thread, so the first frame never waits on disk for it.
        getSharedPreferences("family", MODE_PRIVATE)
        tiles = TileCache(this)
        repo = AppRepository(this, prefs)
        repo.tileInvalidator = { tiles.invalidatePackage(it) }
        repo.onScanned = { tiles.prune(it) }
        repo.registerPackageReceiver()
        forgetLastAppOnStandby()
        com.auratv.launcher.util.StartupTrace.mark("app")
    }

    /**
     * Home focuses the app you were last using, so OK reopens it after it closes on its own. After
     * the TV has been switched off that looks like the app is about to open by itself (a TCL woke to
     * a big "YouTube" with its card selected), so standby and a fresh boot forget it and Home starts on the first card.
     */
    private fun forgetLastAppOnStandby() {
        // Started within a few minutes of the TV booting: that is a switch-on too.
        if (android.os.SystemClock.elapsedRealtime() < 3 * 60_000L) prefs.lastLaunchedKey = ""
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                prefs.lastLaunchedKey = ""
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
    }

    private fun currentProcessName(): String = try {
        java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' }
    } catch (e: Exception) { packageName }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isRedirectProcess) return
        // Keep tiles while merely hidden (fast return to Home); drop them under real pressure.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            tiles.trimMemory()
        }
    }
}
