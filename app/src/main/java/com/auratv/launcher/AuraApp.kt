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
        tiles = TileCache(this)
        repo = AppRepository(this, prefs)
        repo.tileInvalidator = { tiles.invalidatePackage(it) }
        repo.onScanned = { tiles.prune(it) }
        repo.registerPackageReceiver()
        com.auratv.launcher.util.StartupTrace.mark("app")
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
