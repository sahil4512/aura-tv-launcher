package com.auratv.launcher.redirect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.auratv.launcher.R
import com.auratv.launcher.settings.Prefs

/**
 * Aura's Home-button redirect, running in its own small process (":redirect", a few MB) so the
 * launcher UI can be reclaimed under memory pressure without losing the Home button.
 *
 * Detection reads UsageStats foreground events (needs the GET_USAGE_STATS app-op, granted once
 * over ADB). Polling is adaptive and stops completely while the screen is off.
 */
class HomeRedirectService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var usm: UsageStatsManager? = null
    private var screenOn = true
    private var lastEventTs = 0L
    private var started = false
    private var foreground = false
    private val usageAccess = UsageAccessCache()

    private val pollRunnable = Runnable { poll() }
    /** One-off re-check shortly after an accessibility window event. It replaces the pending poll, so
     *  it never starts a second poll chain (each poll() schedules exactly one successor). */
    private val recheckRunnable = Runnable { handler.removeCallbacks(pollRunnable); poll() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            handler.removeCallbacks(pollRunnable)
            handler.removeCallbacks(recheckRunnable)
            if (screenOn) handler.post(pollRunnable) // check immediately on wake (Fire OS may show Amazon Home)
            Log.i(RedirectRuntime.TAG, "screen ${if (screenOn) "on: polling" else "off: polling stopped"}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("aura-redirect", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(thread.looper)
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).let {
            @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= 20) it.isInteractive else it.isScreenOn
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HomeRedirect.ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            HomeRedirect.ACTION_ALLOW_AMAZON -> {
                handler.post { RedirectRuntime.policy(this).allowAmazonOnce(System.currentTimeMillis()) }
                if (!started) { stopSelfIfDisabled(); }
                return START_STICKY
            }
        }
        // Explicit config from the UI, or (sticky restart / boot) the saved preferences.
        val cfg = intent?.let { HomeRedirect.Config.from(it) } ?: HomeRedirect.Config.from(Prefs(this))
        RedirectRuntime.config = cfg
        if (!cfg.enabled) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        RedirectRuntime.policy(this).apply {
            bypassDoublePress = cfg.bypassDoublePress
            homePackage = cfg.homePackage
            homeClassHint = if (cfg.homePackage == RedirectPolicy.AMAZON_LAUNCHER) "HomeActivity" else ""
        }
        updateForeground(cfg.resident)
        RedirectRuntime.serviceActive = true
        RedirectRuntime.pollNow = {
            handler.removeCallbacks(pollRunnable)
            handler.removeCallbacks(recheckRunnable)
            handler.post(pollRunnable)
            handler.postDelayed(recheckRunnable, 150) // usage event may land a moment after the window change
        }
        if (!started) {
            started = true
            Log.i(RedirectRuntime.TAG, "redirect service started (poll ${cfg.activeIntervalMs} ms, idle ${cfg.idleIntervalMs} ms)")
        }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    private fun stopSelfIfDisabled() {
        if (!RedirectRuntime.config.enabled) stopSelf()
    }

    private fun poll() {
        val cfg = RedirectRuntime.config
        if (!cfg.enabled || !screenOn) return
        val u = usm
        if (u == null || !usageAccess.get(SystemClock.elapsedRealtime()) { HomeRedirect.hasUsageAccess(this) }) {
            handler.postDelayed(pollRunnable, 10_000) // waiting for the one-time ADB grant
            return
        }
        val now = System.currentTimeMillis()
        val policy = RedirectRuntime.policy(this)
        try {
            val begin = if (lastEventTs == 0L) now - 60_000 else lastEventTs - 1_000
            val events = u.queryEvents(begin, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                if (e.timeStamp < lastEventTs) continue
                if (e.timeStamp == lastEventTs && lastEventTs != 0L && e.packageName == policy.foregroundPackage) continue
                if (com.auratv.launcher.BuildConfig.DEBUG) Log.d(RedirectRuntime.TAG, "fg ${e.packageName}/${e.className} @${e.timeStamp % 100_000}")
                policy.onForeground(e.packageName, e.className, e.timeStamp)
                lastEventTs = e.timeStamp
            }
            val d = policy.decide(now)
            if (d.redirect) RedirectRuntime.launchAura(this, "usage:${d.reason}", d.homeSince)
            else if (d.reason.startsWith("loop-guard-tripped")) Log.w(RedirectRuntime.TAG, "redirect loop detected: paused for 60 s")
        } catch (t: Throwable) {
            Log.w(RedirectRuntime.TAG, "poll failed", t)
        }
        // Adaptive: Aura in front → only Home can bring Amazon up, and that start is held ~5 s by the
        // system anyway, so poll slowly. Another app in front → poll at the configured speed.
        val auraFront = policy.foregroundPackage == packageName
        val interval = when {
            RedirectRuntime.accessibilityConnected -> 3_000L // events arrive from accessibility; polling is only a safety net
            auraFront -> cfg.idleIntervalMs
            else -> cfg.activeIntervalMs
        }
        handler.postDelayed(pollRunnable, interval)
    }

    private fun updateForeground(resident: Boolean) {
        if (resident && !foreground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foreground = true
        } else if (!resident && foreground) {
            stopForeground(true)
            foreground = false
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Home button", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Aura TV")
            .setContentText("Home button opens Aura")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun shutdown() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(recheckRunnable)
        RedirectRuntime.serviceActive = false
        RedirectRuntime.pollNow = null
        RedirectRuntime.config = RedirectRuntime.config.copy(enabled = false)
        if (foreground) { stopForeground(true); foreground = false }
        started = false
    }

    override fun onDestroy() {
        shutdown()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL = "aura_home"
    }
}
