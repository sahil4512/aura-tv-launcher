package com.auratv.launcher.util

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Cold-start checkpoints, logged once as one line ("startup: app 140 · views 310 · rows 620 · …" in
 * ms since the process started) so slow devices can be profiled from a plain `adb logcat`.
 */
object StartupTrace {
    private val t0 = if (Build.VERSION.SDK_INT >= 24) Process.getStartElapsedRealtime() else SystemClock.elapsedRealtime()
    private val marks = ArrayList<Pair<String, Long>>()
    private var done = false

    fun mark(name: String) {
        if (!done) marks += name to (SystemClock.elapsedRealtime() - t0)
    }

    private var readyTag: String? = null
    private var drawn = false

    /** Content is bound ([tag] for the log); the line is written once the first frame is drawn too. */
    fun ready(tag: String) { mark("ready"); readyTag = tag; if (drawn) finish(tag) }

    /** The first frame has been drawn. */
    fun drawn() { mark("drawn"); drawn = true; readyTag?.let { finish(it) } }

    fun finish(tag: String) {
        if (done) return
        done = true
        Log.i(tag, "startup: " + marks.joinToString(" · ") { "${it.first} ${it.second}" })
    }
}
