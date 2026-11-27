package com.auratv.launcher.redirect

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.auratv.launcher.R

/**
 * Optional full-screen cover for the Home-button redirect (":redirect" process only).
 *
 * Fire OS always starts Amazon Home first and holds other apps back for a few seconds. Instead of
 * racing that, the cover is drawn over Amazon Home the moment it is detected, in GlowHome's own
 * launch colours, and removed once GlowHome has drawn its first frame (or after [MAX_SHOW_MS]).
 *
 * Needs the overlay app-op, granted once over ADB on Fire TV:
 *   adb shell appops set <package> SYSTEM_ALERT_WINDOW allow
 *
 * The cover takes key focus so remote presses can't silently open things on the hidden Amazon
 * screen. Back removes it and means "stay on Amazon Home": the redirect already requested can't be
 * withdrawn, so a marker file tells GlowHome to step aside when it arrives. In kids mode Back is
 * swallowed like any other key (no way out to Amazon Home).
 */
object CoverOverlay {
    /** Longer than Fire OS's ~5 s app-switch hold, so the cover never drops just before GlowHome arrives. */
    const val MAX_SHOW_MS = 7_000L
    /** After GlowHome reports its first frame, keep the cover a moment so the compositor shows GlowHome first. */
    const val HIDE_AFTER_ARRIVAL_MS = 120L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: View? = null
    private val timeout = Runnable { hideNow("timeout") }

    fun permitted(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** What a key press does while the cover is up. Pure (unit tested). */
    enum class KeyAction { STAY_ON_AMAZON, CONSUME }
    fun keyAction(keyCode: Int, kidsMode: Boolean): KeyAction =
        if (!kidsMode && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) KeyAction.STAY_ON_AMAZON
        else KeyAction.CONSUME

    /** GlowHome arrival for a redirect requested at [requestedAt] should step aside if Back was pressed after it. Pure. */
    fun cancelsRedirect(cancelledAt: Long, requestedAt: Long): Boolean =
        cancelledAt > 0L && requestedAt > 0L && cancelledAt >= requestedAt && cancelledAt - requestedAt <= MAX_SHOW_MS

    private const val CANCEL_FILE = "redirect_cancelled_at"

    /** Wall-clock time Back was last pressed on the cover (0 if never). Shared by both processes via a file. */
    fun cancelledAt(ctx: Context): Long = try {
        java.io.File(ctx.filesDir, CANCEL_FILE).readText().trim().toLong()
    } catch (_: Exception) { 0L }

    fun clearCancel(ctx: Context) { try { java.io.File(ctx.filesDir, CANCEL_FILE).delete() } catch (_: Exception) {} }

    private fun stayOnAmazon(ctx: Context) {
        val now = System.currentTimeMillis()
        try { java.io.File(ctx.filesDir, CANCEL_FILE).writeText(now.toString()) } catch (_: Exception) {}
        // Tell the watcher (on its own thread) not to redirect this Amazon Home visit again.
        try { ctx.startService(android.content.Intent(ctx, HomeRedirectService::class.java).setAction(HomeRedirect.ACTION_ALLOW_AMAZON)) } catch (_: Exception) {}
        hideNow("back: staying on Amazon Home")
    }

    /** Shows the cover (no-op without permission). Runs [then] on the main thread once it is added. */
    fun show(ctx: Context, then: () -> Unit) {
        val app = ctx.applicationContext
        val run = Runnable {
            try { showNow(app) } catch (t: Throwable) { Log.w(RedirectRuntime.TAG, "cover failed", t); removeView() }
            then()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.postAtFrontOfQueue(run)
    }

    fun hide(reason: String, delayMs: Long = 0L) {
        main.postDelayed({ hideNow(reason) }, delayMs)
    }

    private fun showNow(ctx: Context) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, MAX_SHOW_MS)
        if (view != null) return
        if (!permitted(ctx)) {
            Log.i(RedirectRuntime.TAG, "cover skipped: overlay permission not granted")
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            title = "GlowHome cover"
        }
        val v = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_UP &&
                    keyAction(event.keyCode, RedirectRuntime.config.kidsMode) == KeyAction.STAY_ON_AMAZON) stayOnAmazon(ctx)
                return true
            }
        }.apply {
            setBackgroundResource(R.drawable.launch_background)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(TextView(ctx).apply {
                text = "Opening GlowHome…"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 18f
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        wm.addView(v, lp)
        v.requestFocus()
        view = v
        Log.i(RedirectRuntime.TAG, "cover shown")
    }

    private fun hideNow(reason: String) {
        main.removeCallbacks(timeout)
        if (view == null) return
        removeView()
        Log.i(RedirectRuntime.TAG, "cover hidden ($reason)")
    }

    private fun removeView() {
        val v = view ?: return
        view = null
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) } catch (_: Exception) {}
    }
}
