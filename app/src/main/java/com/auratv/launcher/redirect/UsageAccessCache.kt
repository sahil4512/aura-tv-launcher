package com.auratv.launcher.redirect

/**
 * Caches the usage-access (AppOps) check so the redirect poll doesn't make a binder call on every
 * tick. A grant is re-checked every [grantedTtlMs] (it can be revoked over ADB). A denial is kept for
 * [deniedTtlMs]: shorter than the 10 s wait-for-grant poll, so every scheduled retry re-checks and a
 * restored grant is noticed on the next one, while bursts of extra polls (screen-on, accessibility
 * window events) reuse the cached denial instead of calling AppOps each time.
 * A new service instance starts with an empty cache. Pure logic (unit tested).
 */
class UsageAccessCache(
    private val grantedTtlMs: Long = 60_000L,
    private val deniedTtlMs: Long = 9_000L,
) {
    private var granted = false
    private var checkedAt = 0L
    private var checked = false

    fun get(now: Long, check: () -> Boolean): Boolean {
        val ttl = if (granted) grantedTtlMs else deniedTtlMs
        if (checked && now - checkedAt in 0 until ttl) return granted
        granted = check()
        checkedAt = now
        checked = true
        return granted
    }
}
