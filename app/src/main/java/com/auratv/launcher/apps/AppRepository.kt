package com.auratv.launcher.apps

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.util.Bg
import java.io.File

/**
 * Discovers launchable apps with PackageManager (both LEANBACK_LAUNCHER and LAUNCHER),
 * persists a tiny cache so the home row can render instantly on cold start, and listens
 * for install/uninstall broadcasts instead of polling.
 */
class AppRepository(private val ctx: Context, private val prefs: Prefs) {

    fun interface Listener {
        fun onAppsChanged(apps: List<AppEntry>)
    }

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    private val listeners = ArrayList<Listener>()
    private val cacheFile = File(ctx.filesDir, "apps.tsv")
    private var allowedPackages: Set<String>? = null

    /** Switch catalogs before loading Home. Only the full catalog owns the disk cache and favourites. */
    fun setAllowedPackages(packages: Set<String>?) {
        if (packages == allowedPackages) return
        allowedPackages = packages?.toSet()
        apps = emptyList()
    }

    private var scanning = false
    private var rescanRequested = false
    var tileInvalidator: ((String) -> Unit)? = null
    /** Called on the main thread after every successful scan (used to prune the tile disk cache). */
    var onScanned: ((List<AppEntry>) -> Unit)? = null

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Synchronous, ~1 ms: reads the last known app list so the first frame already has tiles. */
    fun loadCached(): List<AppEntry> {
        if (apps.isNotEmpty()) return apps
        apps = try {
            val allowed = allowedPackages
            if (cacheFile.exists()) cacheFile.useLines { lines ->
                lines.mapNotNull { AppEntry.parse(it) }
                    .filter { it.packageName !in AppOrdering.NEVER_SHOW && (allowed == null || it.packageName in allowed) }
                    .toList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return apps
    }

    /** Full scan on the background thread; results delivered on the main thread. */
    fun refresh() {
        if (scanning) {
            rescanRequested = true
            return
        }
        scanning = true
        val allowed = allowedPackages
        Bg.apps.execute {
            val t0 = System.nanoTime()
            val result = try { scan(allowed) } catch (e: Exception) { Log.w(TAG, "scan failed", e); null }
            Log.i(TAG, "scan: ${result?.size} apps in ${(System.nanoTime() - t0) / 1_000_000} ms")
            Bg.main.post {
                scanning = false
                if (result != null && allowed == allowedPackages) {
                    if (allowed == null) {
                        apply(result)
                        onScanned?.invoke(result)
                    } else if (result != apps) {
                        apps = result
                        listeners.toList().forEach { it.onAppsChanged(result) }
                    }
                }
                if (rescanRequested) {
                    rescanRequested = false
                    refresh()
                }
            }
        }
    }

    private fun apply(result: List<AppEntry>) {
        val changed = result != apps
        apps = result
        if (changed) {
            Bg.apps.execute {
                try { cacheFile.writeText(result.joinToString("\n") { it.serialize() }) } catch (_: Exception) {}
            }
        }
        // Auto-add newly installed TV apps to favourites.
        val known = prefs.knownApps
        if (prefs.favoritesSeeded && prefs.autoAddNew) {
            val fresh = AppOrdering.newlyInstalled(known, result).filter { it.leanback }
            if (fresh.isNotEmpty()) {
                val favs = prefs.favorites.toMutableList()
                fresh.forEach { if (it.key !in favs) favs.add(it.key) }
                prefs.favorites = favs
            }
        }
        val keys = result.map { it.key }
        if (keys.toHashSet() != known.toHashSet()) prefs.knownApps = keys
        if (!prefs.favoritesSeeded && result.isNotEmpty()) {
            prefs.favorites = AppOrdering.seedFavorites(result)
            prefs.favoritesSeeded = true
        }
        if (changed || !prefs.favoritesSeeded) listeners.toList().forEach { it.onAppsChanged(result) }
    }

    private fun scan(allowed: Set<String>?): List<AppEntry> {
        val pm = ctx.packageManager
        val byPkg = LinkedHashMap<String, AppEntry>()
        fun add(ri: android.content.pm.ResolveInfo, isLean: Boolean) {
            val ai = ri.activityInfo ?: return
            val pkg = ai.packageName
            if (pkg in AppOrdering.NEVER_SHOW || !ai.enabled || !ai.applicationInfo.enabled) return
            if (byPkg.containsKey(pkg)) return // leanback activity wins (added first)
            val appInfo = ai.applicationInfo
            val system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val pi = try { pm.getPackageInfo(pkg, 0) } catch (e: Exception) { null }
            byPkg[pkg] = AppEntry(
                packageName = pkg,
                className = ai.name,
                label = AppEntry.displayLabel(pkg, ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }),
                leanback = isLean,
                system = system,
                installedAt = pi?.firstInstallTime ?: 0L,
                updatedAt = pi?.lastUpdateTime ?: 0L,
            )
        }
        // A scoped query avoids loading labels and resources from every installed TV app.
        for (pkg in allowed ?: listOf(null)) {
            val lean = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg)
            pm.queryIntentActivities(lean, 0).forEach { add(it, true) }
            if (pkg != null && pkg in byPkg) continue
            val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            pm.queryIntentActivities(normal, 0).forEach { add(it, false) }
        }
        return byPkg.values.toList()
    }

    // ---------- package change broadcasts ----------

    private val pendingRefresh = Runnable { refresh() }

    fun registerPackageReceiver() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                Log.i(TAG, "package event ${i.action} $pkg")
                tileInvalidator?.invoke(pkg)
                // Debounce: an update fires REMOVED+ADDED+REPLACED in quick succession.
                Bg.main.removeCallbacks(pendingRefresh)
                Bg.main.postDelayed(pendingRefresh, 600)
            }
        }, f)
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Bg.main.removeCallbacks(pendingRefresh)
                Bg.main.postDelayed(pendingRefresh, 600)
            }
        }, media)
    }

    // ---------- launching ----------

    fun launch(entry: AppEntry, from: View?): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = from?.takeIf { it.width > 0 }?.let {
            ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        return try {
            ctx.startActivity(intent, opts)
            true
        } catch (e: ActivityNotFoundException) {
            fallbackLaunch(entry)
        } catch (e: SecurityException) {
            fallbackLaunch(entry)
        }
    }

    private fun fallbackLaunch(entry: AppEntry): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: pm.getLaunchIntentForPackage(entry.packageName)
        if (i != null) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                refresh()
                return true
            } catch (_: Exception) {}
        }
        Toast.makeText(ctx, "Couldn't open ${entry.label}", Toast.LENGTH_SHORT).show()
        refresh()
        return false
    }

    fun openAppInfo(entry: AppEntry) = startSafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.packageName}"))
    )

    fun uninstall(entry: AppEntry) = startSafely(
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
    )

    private fun startSafely(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Toast.makeText(ctx, "Not available on this device", Toast.LENGTH_SHORT).show()
        false
    }

    fun canUninstall(entry: AppEntry): Boolean = !entry.system

    fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    companion object {
        const val TAG = "AuraApps"
    }
}
