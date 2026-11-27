package com.auratv.launcher.ui

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.redirect.AppRemaps
import com.auratv.launcher.redirect.HomeRedirect
import com.auratv.launcher.util.Bg
import com.auratv.launcher.ui.SettingsPanel.Guide
import com.auratv.launcher.ui.SettingsPanel.RemoteChoices
import com.auratv.launcher.ui.SettingsPanel.Section
import com.auratv.launcher.ui.SettingsPanel.Action
import com.auratv.launcher.ui.SettingsPanel.Info
import com.auratv.launcher.ui.SettingsPanel.Nav
import com.auratv.launcher.ui.SettingsPanel.Page
import com.auratv.launcher.ui.SettingsPanel.Pick

internal object RemoteKeys {
    const val APPS = "all_apps"
    fun id(code: Int): String? = when {
        code == KeyEvent.KEYCODE_ALL_APPS -> APPS
        code == 4082 && Build.MODEL == "UnionTV" && Build.MANUFACTURER.equals("TCL", true) -> APPS
        code in KeyEvent.KEYCODE_PROG_RED..KeyEvent.KEYCODE_PROG_BLUE ||
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
            code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
            code in KeyEvent.KEYCODE_VIDEO_APP_1..KeyEvent.KEYCODE_DEMO_APP_4 ||
            code == KeyEvent.KEYCODE_BOOKMARK -> code.toString()
        else -> null
    }

    fun label(id: String): String {
        if (id == APPS) return "All Apps"
        val code = id.toIntOrNull() ?: return "Remote button"
        return KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
    }
}

/** The setup intercepts supported shortcut keys only on its capture and test pages. */
internal class RemoteButtons(
    private val activity: MainActivity,
    private val push: (Page) -> Unit,
    private val current: () -> Page?,
    private val finish: () -> Unit,
    private val reload: () -> Unit,
) {
    private val prefs get() = activity.prefs
    private val apps get() = AppOrdering.sortForAllApps(activity.repo.apps, 0)
    private var capture: Page? = null
    private var test: Page? = null
    private var testKey = ""
    private var testTarget = ""
    private var tested = false
    private var observedFrom = 0L
    private var consent: Page? = null
    private var detectedPackage: String? = null

    private fun name(pkg: String) = activity.repo.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    private fun mappings(home: Boolean) = AppRemaps.decode(if (home) prefs.homeButtonMappings else prefs.buttonMappings)
    private fun update(home: Boolean, source: String, target: String?) {
        val map = mappings(home).toMutableMap()
        if (target == null) map.remove(source) else map[source] = target
        val lines = map.map { (key, pkg) -> "$key=$pkg" }
        if (home) prefs.homeButtonMappings = lines else {
            prefs.buttonMappings = lines
            HomeRedirect.apply(activity, prefs)
        }
    }

    fun page() = Page("Remote Buttons") {
        buildList {
            val trial = prefs.triedButton
            if (trial.size >= 2) add(Action("Finish tested shortcut", "${RemoteKeys.label(trial[0])} → ${name(trial[1])}") {
                resumeTrial(trial)
            })
            add(RemoteChoices({ beginCapture() }, ::chooseOriginalApp))
            for (home in listOf(true, false)) {
                val saved = mappings(home)
                if (saved.isNotEmpty()) add(Section(if (home) "Buttons on GlowHome" else "App redirects"))
                for ((source, target) in saved) {
                    val label = if (home) RemoteKeys.label(source) else name(source)
                    add(Nav("$label → ${name(target)}", { if (home) "From GlowHome" else "Whenever $label opens" }) {
                        push(Page(label) { listOf(
                            Action("Change app") { pickTarget(home, source) },
                            Action("Restore original action") { update(home, source, null); finish() },
                        ) })
                    })
                }
            }
        }
    }

    private fun chooseOriginalApp() {
        push(Page("Choose the original app") {
            apps.map { app -> appRow(app) { pickTarget(false, app.packageName) } }
        })
    }

    private fun beginCapture(since: Long = 0L) {
        prefs.triedButton = emptyList()
        detectedPackage = null
        prefs.buttonCaptureSince = 0L
        observedFrom = since
        val page = Page("Identify button") {
            buildList {
                add(Guide("Press the button you want to change", "If it opens an app, come back to GlowHome and select that app below."))
                detectedPackage?.let { pkg -> add(Action("Use ${name(pkg)}", "This app opened while GlowHome was away.") { pickTarget(false, pkg) }) }
                add(Nav("Choose the original app", { "For a branded button such as Netflix or Prime Video." }) {
                    chooseOriginalApp()
                })
                add(Info("If the TV handles the button itself and no app opens, try a different button.", ""))
            }
        }
        capture = page
        push(page)
    }

    fun leaving() {
        if (capture != null && current() === capture) {
            observedFrom = System.currentTimeMillis()
            prefs.buttonCaptureSince = observedFrom
        }
    }

    fun returned() {
        if (consent != null && current() === consent) reload()
        val start = observedFrom
        observedFrom = 0L
        if (start != 0L) prefs.buttonCaptureSince = 0L
        val page = capture ?: return
        if (current() !== page || start == 0L || !HomeRedirect.hasUsageAccess(activity)) return
        val end = System.currentTimeMillis()
        val packages = activity.repo.apps.mapTo(HashSet()) { it.packageName }
        Bg.apps.execute {
            val detected = runCatching {
                val service = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val events = service.queryEvents(start - 1500, end)
                val event = UsageEvents.Event()
                var found: String? = null
                while (found == null && events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName in packages)
                        found = event.packageName
                }
                found
            }.getOrNull()
            Bg.main.post {
                if (detected != null && current() === page) { detectedPackage = detected; reload() }
            }
        }
    }

    private fun resumeTrial(trial: List<String>) {
        prefs.triedButton = trial.take(2)
        tryButton(trial[0], trial[1], alreadyTried = true)
    }

    fun resumeSetup() {
        val trial = prefs.triedButton
        if (trial.size >= 2) resumeTrial(trial) else beginCapture(prefs.buttonCaptureSince)
    }

    fun expectingReturn() = current() != null && (current() === capture || current() === test || current() === consent)

    fun handle(key: String, activate: Boolean): Boolean {
        val page = current() ?: return false
        if (page === capture) {
            if (activate) pickTarget(true, key)
            return true
        }
        if (page !== test || key != testKey) return false
        if (activate) {
            tested = activity.launchShortcut(testTarget)
            if (tested) prefs.triedButton = listOf(testKey, testTarget, System.currentTimeMillis().toString())
            reload()
        }
        return true
    }

    private fun appRow(app: AppEntry, selected: Boolean = false, choose: () -> Unit) =
        Pick(app.label, null, selected, { cb ->
            activity.tiles.get(app, Style.tileW(prefs.iconSize), Style.tileH(prefs.iconSize)) { cb(it) }?.let(cb)
        }, choose)

    private fun pickTarget(home: Boolean, source: String) {
        prefs.buttonCaptureSince = 0L
        val label = if (home) RemoteKeys.label(source) else name(source)
        push(Page("Choose an app") {
            val selected = mappings(home)[source]
            listOf(Section("What should $label open?")) + apps.filter { home || it.packageName != source }.map { app ->
                appRow(app, selected == app.packageName) {
                    if (home) tryButton(source, app.packageName) else confirmApp(source, app.packageName)
                }
            }
        })
    }

    private fun tryButton(key: String, target: String, alreadyTried: Boolean = false) {
        tested = alreadyTried
        testKey = key
        testTarget = target
        val page = Page("Try ${RemoteKeys.label(key)}") {
            if (tested) listOf(
                Action("Save shortcut", "${RemoteKeys.label(key)} → ${name(target)} from GlowHome") {
                    update(true, key, target); prefs.triedButton = emptyList(); finish()
                },
                Action("Cancel") { prefs.triedButton = emptyList(); finish() },
            ) else listOf(Guide("${RemoteKeys.label(key)} → ${name(target)}", "Press ${RemoteKeys.label(key)} to try it. Return to GlowHome to save."),
                Action("Cancel") { finish() })
        }
        test = page
        push(page)
    }

    private fun confirmApp(source: String, target: String) {
        val page = Page("Review app redirect") {
            buildList {
                add(Guide("${name(source)} → ${name(target)}", "This redirects every launch of ${name(source)}, including launches from its tile."))
                if (HomeRedirect.hasUsageAccess(activity)) {
                    add(Action("Save shortcut", "Then press the branded button to try it.") {
                        update(false, source, target); finish()
                    })
                } else {
                    add(Action("Allow usage access", "Allow GlowHome, then return here.") {
                        activity.openSystemSettings(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    })
                    add(Info(HomeRedirect.usageAccessCommand(activity), "ADB alternative"))
                }
                add(Action("Cancel") { finish() })
            }
        }
        consent = page
        push(page)
    }
}
