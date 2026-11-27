package com.auratv.launcher.redirect

import com.auratv.launcher.apps.AppOrdering

/** Only act on the final foreground package in a poll. Ignore a replacement's own arrival so that
 * A → B and B → A can coexist without bouncing. Activity changes inside one app are not presses. */
internal class AppRemaps {
    private var foreground: String? = null
    private var expected: String? = null
    private var pending = false

    fun foreground(pkg: String) {
        if (pkg == foreground) return
        foreground = pkg
        pending = pkg != expected
        expected = null
    }

    fun target(mappings: Map<String, String>): String? {
        if (!pending) return null
        pending = false
        val target = mappings[foreground] ?: return null
        if (target == foreground || target in AppOrdering.NEVER_SHOW) return null
        expected = target
        return target
    }

    companion object {
        fun decode(lines: List<String>): Map<String, String> = buildMap {
            for (line in lines) {
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) continue
                val (src, dst) = parts
                if (src.isBlank() || dst.isBlank() || src == dst ||
                    src in AppOrdering.NEVER_SHOW || dst in AppOrdering.NEVER_SHOW) continue
                put(src, dst)
            }
        }
    }
}
