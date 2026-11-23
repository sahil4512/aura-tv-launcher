package com.auratv.launcher.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** Display density, initialised once in [com.auratv.launcher.AuraApp]. */
object Dp {
    var density = 2f
    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
    }
}

val Int.dp: Int get() = (this * Dp.density + 0.5f).toInt()
val Float.dp: Float get() = this * Dp.density

/** TV text is laid out in dp (not sp) so a large system font scale can't break the 10-foot layout. */
fun TextView.sizeDp(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_DIP, v)

/**
 * Background work. Two single-thread executors at background priority so that disk/package
 * work can never compete with the UI thread or the RenderThread for the Fire TV's small CPU.
 */
object Bg {
    val main = Handler(Looper.getMainLooper())

    /** Package scans, tile rendering, settings backup. */
    val apps: ExecutorService = Executors.newSingleThreadExecutor(factory("aura-apps"))

    /** Wallpaper decoding (kept separate so a slow tile render never delays a wallpaper). */
    val decode: ExecutorService = Executors.newSingleThreadExecutor(factory("aura-decode"))

    private fun factory(name: String) = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }
}
