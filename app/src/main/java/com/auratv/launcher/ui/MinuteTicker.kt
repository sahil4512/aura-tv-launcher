package com.auratv.launcher.ui

import com.auratv.launcher.util.Bg

/**
 * Calls [onMinute] right away and then just after every minute boundary, until [stop].
 *
 * Clocks can't rely on the system's ACTION_TIME_TICK alone: a TCL Android TV 9 stopped sending it
 * after a standby (its alarm had not fired for a day and a half) and the Home clock froze. One
 * main-thread post a minute while Home is on screen costs nothing.
 */
internal class MinuteTicker(private val onMinute: () -> Unit) : Runnable {
    private var running = false

    fun start() {
        running = true
        Bg.main.removeCallbacks(this)
        run()
    }

    fun stop() {
        running = false
        Bg.main.removeCallbacks(this)
    }

    override fun run() {
        if (!running) return
        onMinute()
        Bg.main.postDelayed(this, MinuteClock.delayToNextMinute(System.currentTimeMillis()))
    }
}

internal object MinuteClock {
    /** Just past the next whole minute (time zones are whole minutes from UTC). */
    fun delayToNextMinute(nowMs: Long): Long = 60_000L - Math.floorMod(nowMs, 60_000L) + 250L
}
