package com.auratv.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.auratv.launcher.AuraApp
import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.apps.AppRepository
import com.auratv.launcher.apps.RecentApps
import com.auratv.launcher.redirect.DefaultHome
import com.auratv.launcher.redirect.DefaultHomePolicy
import com.auratv.launcher.redirect.HomeRedirect
import com.auratv.launcher.apps.TileCache
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.util.Bg
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp
import com.auratv.launcher.wallpaper.WallpaperCatalog
import com.auratv.launcher.wallpaper.DecodeSize
import com.auratv.launcher.wallpaper.WallpaperController
import com.auratv.launcher.wallpaper.WallpaperView
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    lateinit var prefs: Prefs
    lateinit var repo: AppRepository
    lateinit var tiles: TileCache
    lateinit var wallpaper: WallpaperController

    // Overscan-safe margins (5% horizontal, 5% vertical of a 960×540 dp TV canvas).
    val safeH = 48.dp
    val safeV = 27.dp

    private lateinit var wallpaperView: WallpaperView
    private lateinit var scrim: ScrimView
    private lateinit var homeLayer: FrameLayout
    private lateinit var overlayLayer: FrameLayout
    private lateinit var glowView: GlowBackdrop
    private lateinit var ampm: TextClock
    private lateinit var greeting: TextView
    private lateinit var clockDivider: View
    private var addCell: TileCell? = null
    private lateinit var clock: TextClock
    private lateinit var date: TextClock
    private lateinit var chipApps: ChipButton
    private lateinit var chipWifi: ChipButton
    private lateinit var chipSettings: ChipButton
    private lateinit var heroTitle: TextView
    private lateinit var hint: TextView
    private lateinit var dock: FrameLayout
    private val glowColors = HashMap<String, Int>()
    private lateinit var column: LinearLayout
    private lateinit var favRow: HomeRow
    private lateinit var grid: HomeGrid
    private var lastGridIndex = 0
    private lateinit var appsHeader: TextView
    private lateinit var appsRow: HomeRow
    private lateinit var recentsHeader: TextView
    private lateinit var recentsRow: HomeRow
    private var recentEntries: List<AppEntry> = emptyList()
    private var recentsToken = 0

    private val overlays = ArrayList<Overlay>()
    private var lastHomeFocus: View? = null
    private val cellCache = HashMap<String, TileCell>()
    private var cellSizeKey = ""
    private var allAppsCell: TileCell? = null
    /** All Apps at favourite size, ending the favourites row while there are no recently used apps. */
    private var favAllAppsCell: TileCell? = null
    private var favEntries: List<AppEntry> = emptyList()
    private var moveKey: String? = null
    private var pendingPermission: (() -> Unit)? = null
    private val descriptions = HashMap<String, String>()
    private val thumbs = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private var builtDensityDpi = 0
    private var builtWidthDp = 0
    private var builtHeightDp = 0
    private var createdAt = 0L
    var startupMs: Long? = null
        private set
    private var fullyDrawnReported = false
    private var wallpaperShown = false

    private val appsListener = AppRepository.Listener { bindRows(); refreshRecents() }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.elapsedRealtime()
        val app = application as AuraApp
        prefs = app.prefs
        repo = app.repo
        tiles = app.tiles
        Style.load(prefs)
        com.auratv.launcher.util.Dp.init(this)
        // 0.6.2: Home switches to the solid background once (moving wallpapers cost a slow TV chip
        // a quarter of a core); a wallpaper or glow can still be chosen again in Settings.
        com.auratv.launcher.settings.DeviceProfile.applyOnce(this, prefs)
        if (!prefs.sp.getBoolean("bg_solid_062", false)) {
            prefs.wallpaperType = Prefs.TYPE_SOLID
            prefs.sp.edit().putBoolean("bg_solid_062", true).apply()
        }
        resources.configuration.let { builtDensityDpi = it.densityDpi; builtWidthDp = it.screenWidthDp; builtHeightDp = it.screenHeightDp }

        val trace = com.auratv.launcher.util.StartupTrace
        trace.mark("activity")
        setContentView(buildViews())
        trace.mark("views")
        wallpaper = WallpaperController(wallpaperView, prefs, WallpaperCatalog(this), wallpaperCallbacks)
        wallpaper.refresh(animate = false)

        applyAppearance(rebind = false)
        trace.mark("style")
        repo.loadCached()
        trace.mark("cache")
        bindRows()
        trace.mark("rows")
        repo.addListener(appsListener)
        repo.refresh()
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                trace.mark("layout") // measure + layout of the first frame are done
                window.decorView.post { trace.drawn() } // runs after the first frame's draw pass
                return true
            }
        })
        favRow.post { if (currentFocus == null || currentFocus is ChipButton) focusHome() }
        // The Home-button service lives in its own process; (re)apply the saved configuration.
        HomeRedirect.apply(this, prefs)
        noteRedirectArrival(intent)
        // First start: offer to make Aura the Home app, once Home has drawn and settled.
        Bg.main.postDelayed(homePrompt, HOME_PROMPT_DELAY_MS)
    }

    // =====================================================================
    // "Make GlowHome your home screen" (first start, and Settings → System)
    // =====================================================================

    private var homePromptTries = 0
    /** The prompt is waiting for Aura's window to get focus back (e.g. from a permission dialog). */
    private var homePromptPending = false

    private val homePrompt = Runnable { maybeOfferDefaultHome() }

    /**
     * A system Home step (role dialog, Home settings, chooser) was started and Aura hasn't checked the
     * outcome yet. Kept in prefs: Android starts a new Aura activity when Aura becomes the Home app.
     */
    private var homeStepStarted: Boolean
        get() = prefs.sp.getBoolean("home_step_started", false)
        set(v) { prefs.sp.edit().putBoolean("home_step_started", v).apply() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (homePromptPending) {
            homePromptPending = false
            Bg.main.postDelayed(homePrompt, 800)
        }
        // Back from the system step. (Aura is singleTask, so onActivityResult isn't reliable: check directly.)
        if (homeStepStarted) {
            homeStepStarted = false
            val home = DefaultHome.isAuraHome(this)
            Log.i(TAG, "home step finished, GlowHome is Home: $home")
            if (home) toast("GlowHome is now your home screen")
            (overlays.lastOrNull() as? SettingsPanel)?.reload()
        }
    }

    private fun maybeOfferDefaultHome() {
        if (isFinishing || isDestroyed || prefs.homePromptAnswered) return
        // Another window (a permission dialog) is in front: ask once it is gone.
        if (!hasWindowFocus()) { homePromptPending = true; return }
        // One of Aura's own overlays is open: try again shortly.
        if (overlays.isNotEmpty()) {
            if (++homePromptTries < 150) Bg.main.postDelayed(homePrompt, 2_000)
            return
        }
        val route = DefaultHome.route(this)
        Log.i(TAG, "home prompt: route $route")
        if (DefaultHomePolicy.shouldPromptOnStart(prefs.homePromptAnswered, route)) offerDefaultHome(route, firstStart = true)
    }

    /** Explains and starts the best way this TV allows to make Aura the Home app. */
    fun offerDefaultHome(route: DefaultHomePolicy.Route = DefaultHome.route(this), firstStart: Boolean = false) {
        val p = DefaultHomePolicy.prompt(route)
        val choices = ArrayList<Pair<String, () -> Unit>>()
        p.goLabel?.let { label -> choices += label to { goDefaultHome(route) } }
        choices += (if (p.goLabel == null) "OK" else "Not now") to {}
        // Any answer (a choice or Back) counts: the first-start prompt is never shown again.
        openOverlay(ConfirmOverlay(this, p.heading, p.message, choices,
            onDismissed = { if (firstStart) prefs.homePromptAnswered = true }))
    }

    private fun goDefaultHome(route: DefaultHomePolicy.Route) {
        if (route == DefaultHomePolicy.Route.FIRE_TV) {
            val s = SettingsPanel(this)
            openOverlay(s)
            s.openHomeButton()
        } else if (DefaultHome.start(this, route, REQ_HOME_ROLE)) {
            homeStepStarted = true
        } else {
            offerDefaultHome(DefaultHomePolicy.Route.COMPUTER)
        }
    }

    override fun onStart() {
        super.onStart()
        Bg.main.removeCallbacks(keepLight) // back on Home within a few seconds: leave everything as it was
        wallpaper.onStart()
        noteUserActivity() // motion runs again for a while whenever Home comes back
        refreshRecents()
        updateWifiChip()
        // Screensaver photos are fetched only if Aura is actually the TV's chosen screensaver.
        if (com.auratv.launcher.screensaver.ScreensaverPhotos.isChosenScreensaver(this))
            com.auratv.launcher.screensaver.ScreensaverPhotos.refreshIfStale(this, prefs)
        @Suppress("DEPRECATION")
        registerReceiver(netReceiver, android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION))
    }

    /** Runs a few seconds after Home is left, so the app being opened is already in front (and safe). */
    private val keepLight = Runnable {
        val pkgs = com.auratv.launcher.apps.KeepLight.targets(repo.apps, packageName)
        val app = applicationContext
        Bg.apps.execute { com.auratv.launcher.apps.KeepLight.closeBackground(app, pkgs) }
    }

    override fun onStop() {
        super.onStop()
        if (prefs.keepTvLight) Bg.main.postDelayed(keepLight, 4_000)
        wallpaper.onStop()
        Bg.main.removeCallbacks(idleMotionPause)
        try { unregisterReceiver(netReceiver) } catch (_: Exception) {}
    }

    private val netReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = updateWifiChip()
    }

    /** The Wi-Fi chip shows the network name (or "Offline"); selecting it opens network settings. */
    private fun updateWifiChip() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        val net = try { cm?.activeNetworkInfo } catch (_: Exception) { null }
        @Suppress("DEPRECATION")
        val online = net?.isConnected == true
        @Suppress("DEPRECATION")
        val isWifi = online && net?.type == android.net.ConnectivityManager.TYPE_WIFI
        fun clean(s: String?) = s?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
        val ssid = if (isWifi) {
            // Android 8.1+ reveals the network name only to apps with location permission (and with
            // location services on); older TVs and some vendors also report it in NetworkInfo.extraInfo.
            clean(try {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo?.ssid
            } catch (_: Exception) { null }) ?: clean(@Suppress("DEPRECATION") net?.extraInfo)
        } else null
        chipWifi.text = when { !online -> "Offline"; isWifi -> ssid ?: "Wi-Fi"; else -> "Ethernet" }
        if (isWifi && ssid == null) askForWifiNameOnce()
    }

    /**
     * Asks once for the permission Android 8.1+ requires to read the Wi-Fi network's name. Android's
     * own dialog only says "location", so Aura first explains why and that saying no is fine.
     * Aura uses it for nothing else; declining just keeps the chip saying "Wi-Fi".
     */
    private fun askForWifiNameOnce() {
        if (Build.VERSION.SDK_INT < 27 || overlays.isNotEmpty()) return
        val perm = Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.sp.getBoolean("asked_wifi_name", false)) return
        prefs.sp.edit().putBoolean("asked_wifi_name", true).apply()
        openOverlay(ConfirmOverlay(this, WIFI_NAME_HEADING, WIFI_NAME_MESSAGE, listOf(
            "Continue" to { try { requestPermissions(arrayOf(perm), REQ_WIFI_NAME) } catch (_: Exception) {} },
            "Skip" to {},
        )))
    }

    override fun onDestroy() {
        repo.removeListener(appsListener)
        // Bg.main outlives the activity: a pending hourly tick would keep this instance alive after recreate().
        Bg.main.removeCallbacks(greetingTick)
        Bg.main.removeCallbacks(homePrompt)
        super.onDestroy()
    }

    /**
     * The manifest keeps the activity alive across configuration changes (no flicker on input or
     * locale changes), but every size in the UI is computed from the display density once. When the
     * density or screen size really changes (display settings, a different TV), rebuild with the new
     * metrics instead of keeping stale pixel sizes.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val c = newConfig
        if (c.densityDpi != builtDensityDpi || c.screenWidthDp != builtWidthDp || c.screenHeightDp != builtHeightDp) {
            com.auratv.launcher.util.Dp.init(this)
            recreate()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            wallpaper.releaseMemory()
            thumbs.evictAll()
        }
    }

    /** Home pressed (via Launcher Hijack or a real HOME intent) while Aura is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteRedirectArrival(intent)
        if (moveKey != null) endMove()
        while (overlays.isNotEmpty()) closeOverlay(overlays.last(), animate = false)
        focusHome()
    }

    /**
     * Focus when Home appears: the card of the app you were last using, else the first card. When an
     * app closes on its own (an update, TCL's memory manager), the next OK press then reopens that
     * app instead of whatever happens to be the first card.
     */
    fun focusHome() {
        val key = prefs.lastLaunchedKey
        if (key.isNotEmpty()) {
            if (prefs.homeGrid) {
                val i = (0 until grid.cellCount).firstOrNull { grid.cellAt(it)?.entry?.key == key }
                if (i != null) { grid.cellAt(i)?.requestFocus(); lastGridIndex = i; return }
            } else {
                for (row in listOf(favRow, recentsRow, appsRow)) {
                    if (row.visibility != View.VISIBLE) continue
                    val c = (0 until row.cellCount).mapNotNull { row.cellAt(it) }.firstOrNull { it.entry?.key == key }
                    if (c != null) { c.requestFocus(); return }
                }
            }
        }
        focusFavorites()
    }

    private val wallpaperCallbacks = object : WallpaperController.Callbacks {
        override fun onFirstWallpaperShown() {
            wallpaperShown = true
            // The window background is fully covered from now on: skip drawing it (saves a full-screen fill per frame).
            window.setBackgroundDrawable(null)
            maybeReportFullyDrawn()
        }

        override fun onVideoStruggling(dropPercent: Int) {
            if (prefs.videoWarningShown || overlays.isNotEmpty()) return
            prefs.videoWarningShown = true
            openOverlay(ConfirmOverlay(this@MainActivity, "Video wallpaper is struggling",
                "This device dropped $dropPercent% of video frames. Cinematic image mode looks just as alive and keeps navigation perfectly smooth.",
                listOf("Switch to Cinematic images" to {
                    prefs.wallpaperType = Prefs.TYPE_IMAGE
                    prefs.motionCinematic = true
                    wallpaper.refresh()
                }, "Keep video" to {})))
        }

        override fun onVideoError(message: String) {
            toast("Video wallpaper unavailable: $message")
        }
    }

    private fun maybeReportFullyDrawn() {
        if (fullyDrawnReported || !wallpaperShown || favEntries.isEmpty()) return
        fullyDrawnReported = true
        val start = if (Build.VERSION.SDK_INT >= 24 && SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 15_000)
            Process.getStartElapsedRealtime() else createdAt
        startupMs = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "home ready in $startupMs ms (${if (start == createdAt) "warm" else "cold"})")
        com.auratv.launcher.util.StartupTrace.ready(TAG)
        try { reportFullyDrawn() } catch (_: Exception) {}
    }

    // =====================================================================
    // Views
    // =====================================================================

    private fun buildViews(): View {
        val root = FrameLayout(this)
        wallpaperView = WallpaperView(this)
        glowView = GlowBackdrop(this)
        scrim = ScrimView(this)
        homeLayer = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        overlayLayer = FrameLayout(this)
        root.addView(glowView, full())
        root.addView(wallpaperView, full())
        root.addView(scrim, full())
        root.addView(homeLayer, full())
        root.addView(overlayLayer, full())

        // ---- greeting, clock and date (top-left) ----
        greeting = TextView(this).apply {
            sizeDp(12f); typeface = Style.MEDIUM; letterSpacing = 0.22f; includeFontPadding = false
            isAllCaps = true
        }
        clock = TextClock(this).apply { sizeDp(44f); typeface = Style.LIGHT; includeFontPadding = false }
        ampm = TextClock(this).apply {
            sizeDp(14f); typeface = Style.MEDIUM; includeFontPadding = false; letterSpacing = 0.08f
            format12Hour = "a"; format24Hour = ""
        }
        clockDivider = View(this)
        date = TextClock(this).apply {
            sizeDp(12f); typeface = Style.MEDIUM; letterSpacing = 0.2f; includeFontPadding = false
            isAllCaps = true
            format12Hour = "EEEE  d MMMM"; format24Hour = "EEEE  d MMMM"
        }
        val timeLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(clock, LinearLayout.LayoutParams(wrap(), wrap()))
            addView(ampm, LinearLayout.LayoutParams(wrap(), wrap()).apply { leftMargin = 6.dp; bottomMargin = 6.dp })
            addView(clockDivider, LinearLayout.LayoutParams(1.dp, 22.dp).apply { leftMargin = 16.dp; rightMargin = 16.dp; bottomMargin = 4.dp })
            addView(date, LinearLayout.LayoutParams(wrap(), wrap()).apply { bottomMargin = 7.dp })
        }
        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(greeting, LinearLayout.LayoutParams(wrap(), wrap()))
            // Explicit WRAP so neither line is sized to the other (that once clipped "AM" to "A").
            addView(timeLine, LinearLayout.LayoutParams(wrap(), wrap()).apply { topMargin = 10.dp })
        }
        homeLayer.addView(topLeft, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.TOP or Gravity.START).apply {
            leftMargin = safeH; topMargin = safeV + 4.dp
        })

        // ---- quick actions (top-right): everything else is one press away from Home ----
        chipApps = ChipButton(this, ChipButton.ICON_GRID, "All Apps").apply { setOnClickListener { openAllApps() } }
        chipWifi = ChipButton(this, ChipButton.ICON_WIFI, "Wi-Fi").apply { setOnClickListener { openSystemSettings(Settings.ACTION_WIFI_SETTINGS) } }
        // Settings = the TV's own settings (network, picture, sound, apps). Aura's options: hold Select
        // on this chip, or press the remote's ≡ (menu) key on Home.
        chipSettings = ChipButton(this, ChipButton.ICON_GEAR, "Settings").apply {
            setOnClickListener { openSystemSettings() }
            isLongClickable = true
            setOnLongClickListener { openSettings(); true }
        }
        val chips = listOf(chipApps, chipWifi, chipSettings)
        val chipBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            chips.forEachIndexed { i, c ->
                addView(c, LinearLayout.LayoutParams(wrap(), wrap()).apply { if (i > 0) leftMargin = 10.dp })
                if (c === chipSettings) c.setOnFocusChangeListener { _, has ->
                    if (moveKey != null) return@setOnFocusChangeListener
                    hint.text = "Select: TV settings   ·   Hold Select or press ≡: GlowHome settings"
                    hint.visibility = if (has) View.VISIBLE else View.GONE
                }
                c.setOnKeyListener { _, code, e ->
                    if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> { focusFavorites(keepIndex = true); true }
                        KeyEvent.KEYCODE_DPAD_UP -> true
                        KeyEvent.KEYCODE_DPAD_LEFT -> { chips.getOrNull(i - 1)?.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { chips.getOrNull(i + 1)?.requestFocus(); true }
                        else -> false
                    }
                }
            }
        }
        homeLayer.addView(chipBar, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.TOP or Gravity.END).apply {
            rightMargin = safeH; topMargin = safeV + 8.dp
        })

        // ---- focused app's name, the favourites dock, and the rows that peek in below ----
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        heroTitle = TextView(this).apply {
            sizeDp(30f); typeface = Style.REGULAR; includeFontPadding = false
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(safeH, 0, safeH, 0)
        }
        hint = TextView(this).apply {
            sizeDp(13f); typeface = Style.REGULAR
            setPadding(safeH, 6.dp, safeH, 0)
            visibility = View.GONE
        }
        favRow = HomeRow(this).apply {
            // Each card reserves its focus-glow margin (TileCell.GLOW_PAD) below the art, so the
            // bottom padding is smaller than the top: the cards sit visually centred in the dock.
            setPadding(18.dp, 18.dp, 18.dp, (18 - TileCell.GLOW_PAD).coerceAtLeast(0).dp)
            spacing = 18.dp
            verticalExit = { dir, idx -> rowExit(this, dir, idx) }
            onCellFocused = { idx -> onRowCellFocused(this, idx) }
        }
        grid = HomeGrid(this).apply {
            columns = 4
            spacing = 18.dp - TileCell.GLOW_PAD.dp
            rowGap = 18.dp
            sidePadding = 18.dp
            topPadding = 18.dp
            upExit = { x -> nearestChip(x) }
            onCellFocused = { cell, i -> lastGridIndex = i; updateHero(cell) }
        }
        dock = FrameLayout(this).apply {
            clipChildren = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 22f.dp)
            }
            addView(favRow, FrameLayout.LayoutParams(match(), wrap()))
            addView(grid, FrameLayout.LayoutParams(match(), match()))
        }
        recentsHeader = rowHeader("Recently Used")
        recentsRow = HomeRow(this).apply {
            setPadding(safeH, 12.dp, safeH, 6.dp)
            spacing = 14.dp
            verticalExit = { dir, idx -> rowExit(this, dir, idx) }
            onCellFocused = { idx -> onRowCellFocused(this, idx) }
        }
        appsHeader = rowHeader("Apps")
        appsRow = HomeRow(this).apply {
            setPadding(safeH, 12.dp, safeH, 6.dp)
            spacing = 14.dp
            verticalExit = { dir, idx -> rowExit(this, dir, idx) }
            onCellFocused = { idx -> onRowCellFocused(this, idx) }
        }
        column.addView(heroTitle, LinearLayout.LayoutParams(match(), wrap()))
        column.addView(hint, LinearLayout.LayoutParams(match(), wrap()))
        column.addView(dock, LinearLayout.LayoutParams(match(), wrap()).apply {
            leftMargin = safeH - 18.dp; rightMargin = safeH - 18.dp; topMargin = 14.dp
        })
        column.addView(recentsHeader, LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = 22.dp })
        column.addView(recentsRow, LinearLayout.LayoutParams(match(), wrap()))
        column.addView(appsHeader, LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = 12.dp })
        column.addView(appsRow, LinearLayout.LayoutParams(match(), wrap()))
        homeLayer.addView(column, FrameLayout.LayoutParams(match(), wrap(), Gravity.TOP))
        return root
    }

    /**
     * The focused app's name and the favourites dock sit a little above the middle ("Centred") or
     * lower ("Lower"); Recently Used and Apps rows follow underneath and peek in at the bottom.
     */
    private fun positionColumn() {
        val lp = column.layoutParams as FrameLayout.LayoutParams
        val screenH = resources.displayMetrics.heightPixels
        val dlp = dock.layoutParams as LinearLayout.LayoutParams
        val dockH = if (prefs.homeGrid) screenH - (safeV + 96.dp) - 44.dp - dlp.topMargin - 20.dp else ViewGroup.LayoutParams.WRAP_CONTENT
        if (dlp.height != dockH) { dlp.height = dockH; dock.layoutParams = dlp }
        val top = if (prefs.homeGrid) safeV + 96.dp else (screenH * (if (prefs.rowPosition == 0) 0.27f else 0.35f)).toInt()
        if (lp.topMargin == top && lp.gravity == Gravity.TOP) return
        lp.gravity = Gravity.TOP; lp.topMargin = top; lp.bottomMargin = 0
        column.layoutParams = lp
    }

    private fun rowHeader(text: String) = TextView(this).apply {
        this.text = text
        sizeDp(12f); typeface = Style.MEDIUM; letterSpacing = 0.18f
        isAllCaps = true
        maxLines = 1
        setPadding(safeH, 0, safeH, 0)
    }

    /** The big title above the dock: the focused card's full name (never cut, whatever the card shows). */
    private fun updateHero(cell: TileCell?) {
        val name = cell?.let { it.entry?.label ?: it.contentDescription?.toString() } ?: return
        if (heroTitle.text != name) {
            heroTitle.text = name
            if (Style.animFactor > 0f) {
                heroTitle.alpha = 0.35f
                heroTitle.animate().alpha(1f).setDuration(Style.dur(160)).setInterpolator(Style.easeOut).start()
            }
        }
        updateGlow(cell)
    }

    /** Ambient glow in the focused card's own colour (computed once per app from its artwork). */
    private fun updateGlow(cell: TileCell) {
        if (!wallpaper.isGlow) return
        val key = cellKey(cell)
        val c = glowColors[key] ?: run {
            val b = cell.tile.bitmap ?: return@run null
            (GlowBackdrop.colorOf(b) ?: neutralGlow()).also { glowColors[key] = it }
        } ?: neutralGlow()
        glowView.setGlowColor(c, Style.dur(420))
    }

    private fun neutralGlow(): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(Style.accent, hsv)
        hsv[1] = (hsv[1] * 0.75f).coerceAtMost(0.7f); hsv[2] = 0.55f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun full() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun rowFraction(idx: Int, count: Int) = if (count <= 1) 0f else idx * 2f / (count - 1) - 1f

    /** "Good Evening" etc. Re-evaluated whenever Home becomes visible and on each hour boundary. */
    private val greetingTick = Runnable { updateGreeting() }

    private fun updateGreeting() {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greeting.text = when (h) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Good Night"
        }
        Bg.main.removeCallbacks(greetingTick)
        val cal = java.util.Calendar.getInstance()
        val msToHour = (60 - cal.get(java.util.Calendar.MINUTE)) * 60_000L - cal.get(java.util.Calendar.SECOND) * 1000L
        Bg.main.postDelayed(greetingTick, msToHour + 1_000)
    }

    /** Applies theme, sizes, clock, layout. Cheap enough to call on every settings change (live preview). */
    fun applyAppearance(rebind: Boolean = true) {
        Style.load(prefs)
        val t = Style.theme
        val glow = wallpaper.isGlow
        val backdrop = wallpaper.usesBackdrop
        glowView.solid = !glow
        glowView.visibility = if (backdrop) View.VISIBLE else View.GONE
        wallpaperView.visibility = if (backdrop) View.GONE else View.VISIBLE
        scrim.visibility = if (backdrop) View.GONE else View.VISIBLE
        scrim.rebuild()
        applyDim()
        val is24 = when (prefs.clockFormat) { 1 -> false; 2 -> true; else -> DateFormat.is24HourFormat(this) }
        val fmt = if (is24) "HH:mm" else "h:mm"
        clock.format12Hour = fmt; clock.format24Hour = fmt
        ampm.format12Hour = if (is24) "" else "a"; ampm.format24Hour = if (is24) "" else "a"
        clock.setTextColor(t.text); ampm.setTextColor(Style.withAlpha(t.text, 220))
        val clockVis = if (prefs.showClock) View.VISIBLE else View.GONE
        clock.visibility = clockVis; ampm.visibility = if (is24) View.GONE else clockVis
        date.setTextColor(Style.withAlpha(t.text, 215))
        date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        clockDivider.setBackgroundColor(Style.withAlpha(t.text, 90))
        clockDivider.visibility = if (prefs.showDate && prefs.showClock) View.VISIBLE else View.GONE
        greeting.setTextColor(Style.withAlpha(t.text, 205))
        greeting.visibility = if (prefs.showGreeting) View.VISIBLE else View.GONE
        heroTitle.setTextColor(t.text)
        hint.setTextColor(Style.withAlpha(t.text, 205))
        listOf(chipApps, chipWifi, chipSettings).forEach { it.invalidate() }
        dock.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.argb(if (backdrop) 30 else 46, 255, 255, 255))
            cornerRadius = 22f.dp
            setStroke(1.dp, android.graphics.Color.argb(if (backdrop) 40 else 56, 255, 255, 255))
        }
        appsHeader.setTextColor(Style.withAlpha(t.text, 205))
        recentsHeader.setTextColor(Style.withAlpha(t.text, 205))
        updateGreeting()

        positionColumn()
        cellCache.values.forEach { it.applyTheme() }
        allAppsCell?.applyTheme()
        favAllAppsCell?.applyTheme()
        addCell?.applyTheme()
        if (rebind) bindRows()
        overlays.forEach { if (it !is SettingsPanel) it.onThemeChanged() }
        (currentFocus as? TileCell)?.let { updateGlow(it) } ?: if (glow && glowColors.isEmpty()) glowView.setGlowColor(neutralGlow(), 0) else Unit
    }

    fun applyDim() {
        scrim.dim = prefs.dimPercent / 100f
    }

    // =====================================================================
    // Rows
    // =====================================================================

    fun makeCell(e: AppEntry, w: Int, h: Int, showLabel: Boolean, innerLabel: Boolean = false): TileCell {
        val cell = TileCell(this, e, TileView.KIND_APP)
        cell.bindSize(w, h, showLabel, innerLabel)
        tiles.get(e, w, h) { b ->
            cell.tile.bitmap = b
            if (cell.isFocused) updateGlow(cell) // artwork arrived after focus: light the glow now
        }?.let { cell.tile.bitmap = it }
        return cell
    }

    /** (Re)binds both rows from the repository + preferences, reusing existing cells to avoid flicker. */
    fun bindRows() {
        val all = repo.apps
        val hidden = prefs.hiddenSet()
        val resolved = AppOrdering.resolveFavorites(prefs.favorites, all, hidden)
        if (resolved.migrated != null && all.isNotEmpty()) prefs.favorites = resolved.migrated
        val favEntriesBefore = favEntries
        favEntries = resolved.entries

        val firstRealBind = favEntriesBefore.isEmpty() && favEntries.isNotEmpty()
        val focusedKey = if (firstRealBind) null else (currentFocus as? TileCell)?.let { cellKey(it) }
        val focusedRow = rowOf(currentFocus)
        val w = Style.favW(prefs.iconSize)
        val h = Style.favH(prefs.iconSize)
        val rw = Style.recentW(prefs.iconSize)
        val rh = Style.recentH(prefs.iconSize)
        // Grid cards fill the dock's width in 4 columns (16:9, like the banners).
        val dockInner = resources.displayMetrics.widthPixels - 2 * (safeH - 18.dp) - 2 * 18.dp
        // Each card's view also spans its glow margin (TileCell.GLOW_PAD) to the right, so the grid's
        // spacing is reduced by it: the visible gap stays 18 dp and four cards fit exactly.
        val gw = (dockInner - 3 * 18.dp - TileCell.GLOW_PAD.dp) / 4
        val gh = gw * 9 / 16
        val sizeKey = "$w:$h:$gw:${prefs.showLabels}:${prefs.homeGrid}"
        if (sizeKey != cellSizeKey) {
            cellCache.clear(); allAppsCell = null; favAllAppsCell = null; addCell = null; cellSizeKey = sizeKey
        }

        val used = HashSet<String>()
        fun cellFor(e: AppEntry, inFav: Boolean, prefix: String = if (inFav) "F:" else "A:"): TileCell {
            val k = prefix + e.key
            used.add(k)
            val existing = cellCache[k]
            if (existing != null && existing.entry == e) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                return existing
            }
            val c = if (prefix == "R:") makeCell(e, rw, rh, true, innerLabel = true)
                else if (prefs.homeGrid && (inFav || prefix == "G:")) makeCell(e, gw, gh, false) // name = big title
                else if (inFav) makeCell(e, w, h, false) // the name is the big title above the dock
                else makeCell(e, rw, rh, true, innerLabel = true)
            c.setOnClickListener { if (moveKey != null) endMove() else launch(e, c.tile) }
            c.setOnLongClickListener { if (moveKey == null) openAppMenu(e, inFav); true }
            c.focusListener = { cell, has -> if (has) showInfo(cell.entry, inFav) }
            cellCache[k] = c
            return c
        }

        val favCells = favEntries.map { cellFor(it, true) }.toMutableList()
        val add = addCell ?: TileCell(this, null, TileView.KIND_ADD).also { c ->
            c.bindSize(w, h, false)
            c.setOnClickListener { openOverlay(AllAppsOverlay(this, pickFavorites = true)) }
            addCell = c
        }
        (add.parent as? ViewGroup)?.removeView(add)
        favCells.add(add)

        // Second row: recently used apps, ending with an "All Apps" card. With no recently used apps
        // there is no near-empty second row: All Apps ends the favourites row instead.
        val maxRecents = 7
        val recents = if (prefs.showRecentsRow) recentEntries.filter { it.key !in hidden }.take(maxRecents) else emptyList()
        val allCell = allAppsCell ?: TileCell(this, null, TileView.KIND_ALL_APPS).also { c ->
            c.bindSize(rw, rh, true, innerLabel = true)
            c.setOnClickListener { openAllApps() }
            allAppsCell = c
        }
        val favAll = favAllAppsCell ?: TileCell(this, null, TileView.KIND_ALL_APPS).also { c ->
            c.bindSize(w, h, false)
            c.setOnClickListener { openAllApps() }
            favAllAppsCell = c
        }
        (allCell.parent as? ViewGroup)?.removeView(allCell)
        (favAll.parent as? ViewGroup)?.removeView(favAll)
        if (recents.isEmpty()) {
            favCells.add(favAll)
            recentsRow.setCells(emptyList())
            recentsHeader.visibility = View.GONE
            recentsRow.visibility = View.GONE
        } else {
            recentsRow.setCells(recents.map { cellFor(it, false, "R:") } + allCell)
            recentsHeader.visibility = View.VISIBLE
            recentsRow.visibility = View.VISIBLE
        }
        favRow.setCells(favCells)

        if (prefs.homeGrid) {
            // Every app on Home: favourites first (in your order), then the rest.
            val favKeys = favEntries.map { it.key }.toHashSet()
            val others = AppOrdering.sortForAllApps(
                AppOrdering.visible(all, hidden, prefs.showSystemApps).filter { it.key !in favKeys }, prefs.allAppsSort)
            favRow.setCells(emptyList())
            grid.setCells(favEntries.map { cellFor(it, true) } + others.map { cellFor(it, false, "G:") })
            favRow.visibility = View.GONE
            grid.visibility = View.VISIBLE
            listOf(recentsHeader, recentsRow, appsHeader, appsRow).forEach { it.visibility = View.GONE }
            recentsRow.setCells(emptyList()); appsRow.setCells(emptyList())
        } else {
            grid.setCells(emptyList())
            grid.visibility = View.GONE
            favRow.visibility = View.VISIBLE
        }

        if (prefs.homeGrid) Unit else if (prefs.showAppsRow) {
            val favKeys = favEntries.map { it.key }.toHashSet()
            val others = AppOrdering.sortForAllApps(
                AppOrdering.visible(all, hidden, prefs.showSystemApps).filter { it.key !in favKeys }, prefs.allAppsSort)
            appsRow.setCells(others.map { cellFor(it, false) })
            appsHeader.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
            appsRow.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        } else {
            appsRow.setCells(emptyList())
            appsHeader.visibility = View.GONE
            appsRow.visibility = View.GONE
        }
        positionColumn()
        cellCache.keys.retainAll(used)

        // Restore focus to the same app if it is still there.
        if (overlays.isEmpty() && focusedKey != null && prefs.homeGrid) {
            val target = (0 until grid.cellCount).mapNotNull { grid.cellAt(it) }.firstOrNull { cellKey(it) == focusedKey }
            (target ?: grid.cellAt(0))?.requestFocus()
        } else if (overlays.isEmpty() && focusedKey != null) {
            val row = focusedRow?.takeIf { it.visibility == View.VISIBLE } ?: favRow
            val target = (0 until row.cellCount).mapNotNull { row.cellAt(it) }
                .firstOrNull { cellKey(it) == focusedKey }
            (target ?: favRow.cellAt(0))?.requestFocus()
        } else if (overlays.isEmpty() && (currentFocus == null || firstRealBind)) {
            // First run: the app list arrived after onCreate's initial focus attempt.
            favRow.post { if (overlays.isEmpty()) focusHome() }
        }
        maybeReportFullyDrawn()
    }

    private fun cellKey(c: TileCell): String = c.entry?.key ?: if (c === addCell) "add" else "all"

    private fun visibleRows(): List<HomeRow> =
        listOf(favRow, recentsRow, appsRow).filter { it.visibility == View.VISIBLE && it.cellCount > 0 }

    private fun rowOf(v: View?): HomeRow? = listOf(favRow, recentsRow, appsRow).firstOrNull { v != null && v.parent === it.strip }

    /** UP/DOWN between rows: nearest tile by horizontal position; UP from the top row → settings. */
    private fun rowExit(row: HomeRow, dir: Int, idx: Int): View? {
        val rows = visibleRows()
        val i = rows.indexOf(row)
        val target = when (dir) {
            View.FOCUS_UP -> if (i <= 0) return nearestChip(row.cellCenterX(idx)) else rows[i - 1]
            View.FOCUS_DOWN -> rows.getOrNull(i + 1) ?: return row.cellAt(idx)
            else -> return null
        }
        return target.cellAt(target.nearestIndexTo(row.cellCenterX(idx)))
    }

    /** ▲ from the top row: the chip closest to the card above which focus was. */
    private fun nearestChip(x: Float): View = listOf(chipApps, chipWifi, chipSettings).minByOrNull {
        val loc = IntArray(2); it.getLocationInWindow(loc)
        kotlin.math.abs(loc[0] + it.width / 2f - x)
    } ?: chipSettings

    private fun onRowCellFocused(row: HomeRow, idx: Int) {
        updateHero(row.cellAt(idx))
        if (!wallpaper.isGlow) wallpaperView.parallaxTo(rowFraction(idx, row.cellCount), 900)
        ensureRowVisible(row)
    }

    /** With several rows on a small TV canvas, slide the column up just enough to show the focused row. */
    private fun ensureRowVisible(row: HomeRow) {
        if (row === favRow) {
            if (column.translationY != 0f) column.animate().translationY(0f).setDuration(Style.dur(FocusSpec.SCROLL_MS)).setInterpolator(Style.easeOut).start()
            return
        }
        val bottomLimit = homeLayer.height - safeV
        val rowBottom = column.top + row.bottom // layout position, ignoring the current translation
        val needed = (bottomLimit - rowBottom).toFloat().coerceAtMost(0f)
        if (needed != column.translationY) column.animate().translationY(needed).setDuration(Style.dur(FocusSpec.SCROLL_MS)).setInterpolator(Style.easeOut).start()
    }

    private var lastFavIndex = 0

    fun focusFavorites(keepIndex: Boolean = false) {
        if (prefs.homeGrid) {
            val i = if (keepIndex) lastGridIndex.coerceIn(0, (grid.cellCount - 1).coerceAtLeast(0)) else 0
            grid.cellAt(i)?.requestFocus()
            if (!keepIndex) grid.scrollToRow(0, true)
            return
        }
        val idx = if (keepIndex) lastFavIndex.coerceIn(0, (favRow.cellCount - 1).coerceAtLeast(0)) else 0
        val c = favRow.cellAt(idx) ?: return
        c.requestFocus()
        if (!keepIndex) favRow.scrollToIndex(0, true)
    }

    fun showInfo(e: AppEntry?, inFav: Boolean) {
        if (e == null) return
        if (inFav && !prefs.homeGrid) lastFavIndex = favRow.indexOf(currentFocus ?: return).coerceAtLeast(0)
        if (moveKey != null) {
            hint.text = "Moving ${e.label}   ·   ◀ ▶ to reposition, Select to finish"
            hint.visibility = View.VISIBLE
        }
    }

    // =====================================================================
    // App actions
    // =====================================================================

    fun launch(e: AppEntry, from: View?) {
        if (repo.launch(e, from)) {
            prefs.launchHistory = RecentApps.recordLaunch(prefs.launchHistory, e.packageName)
            prefs.lastLaunchedKey = e.key
        }
    }

    /** Recently used apps: system usage history if granted, else Aura's own launch history. Off the UI thread. */
    fun refreshRecents() {
        if (!prefs.showRecentsRow) {
            if (recentEntries.isNotEmpty()) { recentEntries = emptyList(); bindRows() }
            return
        }
        val token = ++recentsToken
        val apps = repo.apps
        val history = prefs.launchHistory
        val hidden = prefs.hiddenSet()
        Bg.apps.execute {
            val system = RecentApps.lastUsedFromSystem(this)
            val used = if (system.isNotEmpty()) system else RecentApps.fromHistory(history)
            val ranked = RecentApps.rank(used, apps, RecentApps.homeApps(this) + packageName, hidden, 8)
            Bg.main.post {
                if (token != recentsToken || isDestroyed) return@post
                if (ranked.map { it.key } != recentEntries.map { it.key }) {
                    recentEntries = ranked
                    bindRows()
                }
            }
        }
    }

    /** Records how long a Home-button redirect took, measured from Amazon Home appearing to Aura's first frame. */
    private fun noteRedirectArrival(i: Intent?) {
        val detectedAt = i?.getLongExtra(HomeRedirect.EXTRA_DETECTED_AT, 0L) ?: 0L
        if (detectedAt <= 0L) return
        val requestedAt = i!!.getLongExtra(HomeRedirect.EXTRA_REQUESTED_AT, 0L)
        val source = i.getStringExtra(HomeRedirect.EXTRA_SOURCE) ?: "?"
        i.removeExtra(HomeRedirect.EXTRA_DETECTED_AT)
        window.decorView.post {
            val shown = System.currentTimeMillis()
            val total = shown - detectedAt
            val detect = requestedAt - detectedAt
            Log.i(TAG, "redirect arrived: total $total ms (detect $detect ms, system hold + draw ${shown - requestedAt} ms) via $source")
            val line = "$shown|$source|$total|$detect"
            prefs.redirectLog = (listOf(line) + prefs.redirectLog).take(12)
        }
    }

    fun isFavorite(e: AppEntry) = e.key in prefs.favorites

    fun setFavorite(e: AppEntry, fav: Boolean) {
        val list = prefs.favorites.toMutableList()
        if (fav && e.key !in list) list.add(e.key)
        if (!fav) list.remove(e.key)
        prefs.favorites = list
        bindRows()
        if (overlays.isEmpty() && !fav) focusFavorites(keepIndex = true)
        (overlays.lastOrNull() as? AllAppsOverlay)?.rebuild()
        toast(if (fav) "${e.label} added to Favourites" else "${e.label} removed from Favourites")
    }

    fun setHidden(e: AppEntry, hide: Boolean) {
        val h = prefs.hidden.toMutableList()
        if (hide) { if (e.key !in h) h.add(e.key) } else h.remove(e.key)
        prefs.hidden = h
        if (hide) prefs.favorites = prefs.favorites - e.key
        bindRows()
        overlays.forEach { if (it is AllAppsOverlay) it.rebuild() }
        if (hide && overlays.isEmpty()) focusFavorites(keepIndex = true)
        toast(if (hide) "${e.label} hidden" else "${e.label} is visible again")
    }

    fun resetFavorites() {
        prefs.favorites = AppOrdering.seedFavorites(repo.apps)
        bindRows()
        focusFavorites()
    }

    // ---- move mode ----

    fun startMove(e: AppEntry) {
        val idx = favEntries.indexOfFirst { it.key == e.key }
        if (idx < 0) return
        moveKey = e.key
        val cell = (if (prefs.homeGrid) grid.cellAt(idx) else favRow.cellAt(idx)) ?: return
        cell.requestFocus()
        cell.tile.moving = true
        showInfo(e, true)
    }

    private fun moveBy(d: Int) {
        val key = moveKey ?: return
        val from = favEntries.indexOfFirst { it.key == key }
        val to = from + d
        if (from < 0 || to < 0 || to >= favEntries.size) {
            (if (prefs.homeGrid) grid.cellAt(from) else favRow.cellAt(from))?.bump(d)
            return
        }
        prefs.favorites = AppOrdering.move(favEntries.map { it.key }, from, to)
        favEntries = AppOrdering.move(favEntries.map { it.key }, from, to).map { k -> favEntries.first { it.key == k } }
        if (prefs.homeGrid) {
            bindRows() // the moved card is the same view and keeps focus
            lastGridIndex = to
            return
        }
        // Move the *neighbour* rather than the focused cell so focus never detaches.
        val strip = favRow.strip
        val neighbour = strip.getChildAt(to)
        strip.removeViewAt(to)
        strip.addView(neighbour, from)
        for (i in 0 until strip.childCount) {
            (strip.getChildAt(i).layoutParams as LinearLayout.LayoutParams).leftMargin = if (i == 0) 0 else favRow.spacing
        }
        strip.requestLayout()
        favRow.post { favRow.scrollToIndex(to, true) }
        lastFavIndex = to
    }

    private fun endMove() {
        val key = moveKey ?: return
        moveKey = null
        hint.visibility = View.GONE
        cellCache["F:$key"]?.let { it.tile.moving = false; showInfo(it.entry, true) }
    }

    // =====================================================================
    // Overlays
    // =====================================================================

    fun openOverlay(o: Overlay) {
        if (overlays.isEmpty()) {
            lastHomeFocus = currentFocus
            homeLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            (overlays.last().root as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        overlays.add(o)
        overlayLayer.addView(o.root, full())
        if (Style.animFactor > 0f) {
            o.root.alpha = 0f
            o.root.animate().alpha(1f).setDuration(Style.dur(200)).setInterpolator(Style.easeOut).start()
            if (o is SettingsPanel) {
                o.root.translationX = 36f.dp
                o.root.animate().translationX(0f).setDuration(Style.dur(260)).setInterpolator(Style.easeOut).start()
            }
        }
        wallpaperView.frozen = overlays.any { it.coversWallpaper }
        o.focusInitial()
    }

    fun closeOverlay(o: Overlay, animate: Boolean = true) {
        if (!overlays.remove(o)) return
        val r = o.root
        (r as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (animate && Style.animFactor > 0f) {
            r.animate().alpha(0f).setDuration(Style.dur(160)).withEndAction { overlayLayer.removeView(r) }.start()
        } else overlayLayer.removeView(r)
        o.onClosed()
        if (overlays.isEmpty()) {
            homeLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val f = lastHomeFocus?.takeIf { it.isAttachedToWindow && it.isShown }
            if (f != null) f.requestFocus() else focusFavorites(keepIndex = true)
        } else {
            val top = overlays.last()
            (top.root as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (top is SettingsPanel) top.reload() else top.focusInitial()
        }
        wallpaperView.frozen = overlays.any { it.coversWallpaper }
    }

    fun openSettings() = openOverlay(SettingsPanel(this))
    fun openAllApps() = openOverlay(AllAppsOverlay(this))
    fun openAppMenu(e: AppEntry, inFav: Boolean) = openOverlay(AppMenuOverlay(this, e, inFav))

    fun rebuildHome() {
        bindRows()
        overlays.forEach { if (it is AllAppsOverlay) it.rebuild() }
    }

    /** Fire TV / Android settings; [action] e.g. [Settings.ACTION_WIFI_SETTINGS] for the network page. */
    fun openSystemSettings(action: String = Settings.ACTION_SETTINGS) {
        // On Fire OS the Settings intent is forwarded to Amazon Home's Settings tab; tell the Home
        // redirect to leave Amazon Home alone this once, or it would bounce straight back to Aura.
        if (prefs.redirectEnabled && HomeRedirect.isFireOs(this)) {
            try { startService(Intent(this, com.auratv.launcher.redirect.HomeRedirectService::class.java).setAction(HomeRedirect.ACTION_ALLOW_AMAZON)) } catch (_: Exception) {}
        }
        val intents = listOf(
            Intent(action),
            Intent(Settings.ACTION_SETTINGS),
            Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.SettingsActivity"),
        )
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
        toast("System settings are not available")
    }

    // =====================================================================
    // Keys
    // =====================================================================

    /**
     * The wallpaper's slow pan redraws the whole screen 30 times a second. Nobody watches it when the
     * remote hasn't been touched for a while, so it pauses then and resumes on the next key press:
     * on a slow TV chip that frees about a quarter of a CPU core whenever Home just sits there.
     */
    private val idleMotionPause = Runnable { wallpaperView.idlePaused = true }

    private fun noteUserActivity() {
        Bg.main.removeCallbacks(idleMotionPause)
        if (wallpaperView.idlePaused) wallpaperView.idlePaused = false
        Bg.main.postDelayed(idleMotionPause, IDLE_MOTION_PAUSE_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) noteUserActivity()
        if (moveKey != null && overlays.isEmpty()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) moveBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) endMove()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val top = overlays.lastOrNull()
        if (top != null && top.onKeyDown(keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            when {
                top is SettingsPanel -> closeOverlay(top)
                top is AllAppsOverlay -> top.focusedEntry()?.let { openAppMenu(it, false) }
                top != null -> closeOverlay(top)
                else -> {
                    val f = currentFocus as? TileCell
                    val e = f?.entry
                    if (e != null) openAppMenu(e, isFavorite(e)) else openSettings()
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val top = overlays.lastOrNull()
        when {
            top != null -> if (!top.onBack()) closeOverlay(top)
            moveKey != null -> endMove()
            // A launcher never exits on Back: return focus to the start of the favourites row.
            else -> focusFavorites()
        }
    }

    // =====================================================================
    // Wallpaper helpers for settings
    // =====================================================================

    fun loadThumb(id: String, cb: (Bitmap?) -> Unit) {
        thumbs.get(id)?.let { cb(it); return }
        Bg.decode.execute {
            val b = wallpaper.catalog.thumbnail(id, 192, 108)
            Bg.main.post { b?.let { thumbs.put(id, it) }; cb(b) }
        }
    }

    fun loadFileThumb(f: File, cb: (Bitmap?) -> Unit) {
        val key = "f:" + f.path
        thumbs.get(key)?.let { cb(it); return }
        Bg.decode.execute {
            val b = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                o.inSampleSize = WallpaperCatalog.sampleSize(o.outWidth, o.outHeight, 192, 108)
                o.inJustDecodeBounds = false
                BitmapFactory.decodeFile(f.path, o)?.let { WallpaperCatalog.coverScale(it, 192, 108) }
            } catch (_: Throwable) { null }
            Bg.main.post { b?.let { thumbs.put(key, it) }; cb(b) }
        }
    }

    fun importWallpaper(f: File, done: () -> Unit) {
        toast("Importing ${f.name}…")
        val dm = resources.displayMetrics
        Bg.decode.execute {
            val (iw, ih) = DecodeSize.forScreen(maxOf(dm.widthPixels, dm.heightPixels), minOf(dm.widthPixels, dm.heightPixels))
            val id = try { wallpaper.catalog.import(f, iw, ih) } catch (t: Throwable) { null }
            Bg.main.post {
                if (id == null) { toast("Couldn't read ${f.name}"); return@post }
                if (id !in prefs.importedWallpapers) prefs.importedWallpapers = prefs.importedWallpapers + id
                wallpaper.select(id)
                toast("Wallpaper imported")
                done()
            }
        }
    }

    fun removeImported(id: String) {
        prefs.importedWallpapers = prefs.importedWallpapers - id
        wallpaper.catalog.deleteImported(id)
        thumbs.remove(id)
        if (prefs.wallpaperId == id) wallpaper.select(Prefs.DEFAULT_WALLPAPER)
    }

    // =====================================================================
    // Permissions, backup
    // =====================================================================

    private fun storagePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        Build.VERSION.SDK_INT <= 28 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasStorage(): Boolean = Build.VERSION.SDK_INT < 23 ||
        storagePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun withStoragePermission(then: () -> Unit) {
        if (hasStorage()) { then(); return }
        pendingPermission = then
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(storagePermissions(), REQ_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_WIFI_NAME) { updateWifiChip(); return }
        if (requestCode != REQ_STORAGE) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val p = pendingPermission
        pendingPermission = null
        if (ok) p?.invoke() else toast("Storage access is needed to read your images and videos")
    }

    fun backupFile(): File {
        if (Build.VERSION.SDK_INT <= 28) {
            @Suppress("DEPRECATION")
            return File(File(Environment.getExternalStorageDirectory(), "AuraTV"), "aura-backup.json")
        }
        return File(getExternalFilesDir(null) ?: filesDir, "aura-backup.json")
    }

    fun backupSettings() = withStoragePermission {
        val f = backupFile()
        val json = prefs.toJson().toString(2)
        Bg.apps.execute {
            val ok = try { f.parentFile?.mkdirs(); f.writeText(json); true } catch (e: Exception) { false }
            Bg.main.post { toast(if (ok) "Backed up to ${f.path}" else "Couldn't write ${f.path}") }
        }
    }

    fun restoreSettings() = withStoragePermission {
        val f = backupFile()
        Bg.apps.execute {
            val json = try { JSONObject(f.readText()) } catch (e: Exception) { null }
            Bg.main.post {
                if (json == null) { toast("No backup found at ${f.path}"); return@post }
                try {
                    val n = prefs.restoreJson(json)
                    reloadEverything()
                    toast("Restored $n settings")
                } catch (e: Exception) {
                    toast(e.message ?: "Invalid backup")
                }
            }
        }
    }

    fun resetAll() {
        prefs.resetAll()
        prefs.favorites = AppOrdering.seedFavorites(repo.apps)
        prefs.favoritesSeeded = true
        prefs.knownApps = repo.apps.map { it.key }
        reloadEverything()
        toast("GlowHome TV has been reset")
    }

    private fun reloadEverything() {
        while (overlays.isNotEmpty()) closeOverlay(overlays.last(), animate = false)
        cellSizeKey = ""
        applyAppearance()
        wallpaper.refresh()
        focusFavorites()
        // Restore / reset can change the redirect settings: keep the service and BootReceiver in step.
        HomeRedirect.apply(this, prefs)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "Aura"
        private const val IDLE_MOTION_PAUSE_MS = 45_000L
        private const val REQ_STORAGE = 7
        private const val REQ_WIFI_NAME = 8
        private const val REQ_HOME_ROLE = 9
        private const val HOME_PROMPT_DELAY_MS = 1_500L
        const val WIFI_NAME_HEADING = "Show your Wi-Fi name?"
        const val WIFI_NAME_MESSAGE = "Android asks for location permission before any app can read the name of " +
            "your Wi-Fi network. GlowHome needs it only to show your Wi-Fi name on Home and never uses your location.\n" +
            "If you'd rather not, choose Skip, or \u201cDon't allow\u201d on the next screen. GlowHome works the same; " +
            "the chip just says \u201cWi-Fi\u201d."
    }
}
