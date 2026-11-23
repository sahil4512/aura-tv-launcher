package com.auratv.launcher.redirect

/**
 * Decides when Amazon Home should be replaced by Aura. Pure logic (no Android types) so every
 * loop-prevention rule is unit tested.
 *
 * Feed it foreground changes in order ([onForeground]) and ask [decide] what to do *now*.
 *
 * Fire OS facts this is built around (measured from logs on the target Fire TV, AFTMM / 6.7.1.1):
 *  - The Home key's CLOSE_SYSTEM_DIALOGS broadcast is permission-protected; third-party apps never
 *    receive it. Detection must come from usage events (or accessibility).
 *  - After Home, Android's app-switch protection defers activity starts from background apps for
 *    5 s. A redirect requested during that window is queued by the system and runs at press + 5 s.
 *    So once requested, we must NOT keep re-requesting — we wait, and retry only if it got lost.
 */
class RedirectPolicy(
    private val selfPackage: String,
    var bypassDoublePress: Boolean = true,
    /** Package of the system Home to replace, and a class-name fragment identifying its home screen. */
    var homePackage: String = AMAZON_LAUNCHER,
    var homeClassHint: String = "HomeActivity",
) {
    data class Decision(val redirect: Boolean, val reason: String, val homeSince: Long = 0L)

    private var topPkg: String? = null
    private var topCls: String? = null
    private var topSince = 0L

    /** User chose to stay on Amazon Home; cleared as soon as anything else comes to the front. */
    var amazonAllowed = false
        private set

    /** Set by [allowAmazonOnce]: the next Amazon Home appearance before this time is allowed. */
    private var allowNextHomeUntil = 0L
    private var handledHomeSince = -1L
    private var handledAt = 0L
    private var auraShownAfterRedirectAt = 0L
    private var awaitingAura = false
    private val recent = ArrayDeque<Long>()
    private var pausedUntil = 0L

    fun isAmazonSettings(pkg: String): Boolean = AMAZON_SETTINGS_PREFIXES.any { pkg.startsWith(it) }

    fun isAmazonHome(pkg: String?, cls: String?): Boolean =
        pkg == homePackage && (cls == null || cls.contains(homeClassHint))

    /** A MOVE_TO_FOREGROUND event, in chronological order. [time] is the event's wall-clock time. */
    fun onForeground(pkg: String, cls: String?, time: Long) {
        topPkg = pkg
        topCls = cls
        topSince = time
        when {
            pkg == selfPackage -> {
                if (awaitingAura) {
                    awaitingAura = false
                    auraShownAfterRedirectAt = time
                }
                amazonAllowed = false
            }
            isAmazonHome(pkg, cls) -> {
                if (time <= allowNextHomeUntil) {
                    amazonAllowed = true
                    allowNextHomeUntil = 0
                }
                // Home pressed again shortly after we brought Aura up: the user wants Amazon Home.
                // Amazon Home returning within MIN_REACTION_MS of Aura is not a press the user made
                // after seeing Aura: a Home press during the system's hold makes Android run our queued
                // start and then show Amazon Home on top of it a few ms later. Redirect that again.
                if (bypassDoublePress && auraShownAfterRedirectAt > 0 &&
                    time - auraShownAfterRedirectAt in MIN_REACTION_MS..DOUBLE_PRESS_WINDOW_MS) {
                    amazonAllowed = true
                    auraShownAfterRedirectAt = 0
                }
            }
            pkg == homePackage -> Unit // Amazon Settings etc. inside the launcher package: leave alone
            // Fire TV Settings sub-screens (Wi-Fi, Display, Bluetooth…) live in their own package and
            // Back returns to Amazon Home's Settings tab. They don't end a visit the user chose, or
            // Back would bounce them to Aura in the middle of changing a setting.
            isAmazonSettings(pkg) -> Unit
            else -> amazonAllowed = false
        }
    }

    /**
     * Explicit request (Aura's "Open Amazon Home" / "Fire TV Settings", or holding Home) to show
     * Amazon Home without being redirected. A short window rather than a flag, because Fire OS often
     * passes through trampoline screens (e.g. a settings stub) before Amazon Home appears.
     */
    fun allowAmazonOnce(now: Long) {
        allowNextHomeUntil = now + ALLOW_WINDOW_MS
        if (isAmazonHome(topPkg, topCls)) amazonAllowed = true
    }

    fun decide(now: Long): Decision {
        if (!isAmazonHome(topPkg, topCls)) return Decision(false, "not-home")
        if (amazonAllowed) return Decision(false, "user-chose-amazon")
        if (now < pausedUntil) return Decision(false, "loop-guard-pause")
        if (handledHomeSince == topSince && now - handledAt < RETRY_AFTER_MS) {
            return Decision(false, "pending") // system is holding our start (app-switch protection)
        }
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > LOOP_WINDOW_MS) recent.removeFirst()
        if (recent.size > LOOP_MAX_REDIRECTS) {
            recent.clear()
            pausedUntil = now + LOOP_PAUSE_MS
            return Decision(false, "loop-guard-tripped")
        }
        val retry = handledHomeSince == topSince
        handledHomeSince = topSince
        handledAt = now
        awaitingAura = true
        return Decision(true, if (retry) "retry" else "amazon-home", topSince)
    }

    val isPaused get() = pausedUntil
    val foregroundPackage get() = topPkg

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        /** Fire TV Settings packages (com.amazon.tv.settings, com.amazon.tv.settings.v2). */
        val AMAZON_SETTINGS_PREFIXES = listOf("com.amazon.tv.settings")
        const val DOUBLE_PRESS_WINDOW_MS = 3_000L
        /** Faster than anyone can see Aura and press Home again (measured race: 4–5 ms on API 25). */
        const val MIN_REACTION_MS = 400L
        const val ALLOW_WINDOW_MS = 10_000L
        /** > Fire OS's 5 s app-switch hold, so a deferred start is never duplicated. */
        const val RETRY_AFTER_MS = 7_000L
        const val LOOP_WINDOW_MS = 30_000L
        const val LOOP_MAX_REDIRECTS = 6
        const val LOOP_PAUSE_MS = 60_000L
    }
}
