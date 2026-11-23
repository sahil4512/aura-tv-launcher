package com.auratv.launcher.apps

/**
 * Disk-cache file naming for [TileCache]. Pure (unit tested).
 *
 * `<package>~<generation>~<w>x<h>~<activity hash>.png`, where the generation is the app's
 * update time plus the tile style. One app is cached at several sizes at once (favourites,
 * recents), so only files from another generation are stale; other sizes must survive.
 */
object TileFiles {
    fun generation(updatedAt: Long, style: Int) = "${updatedAt}v$style"

    fun name(e: AppEntry, w: Int, h: Int, style: Int) =
        "${e.packageName}~${generation(e.updatedAt, style)}~${w}x$h~${Integer.toHexString(e.key.hashCode())}.png"

    /** True for this package's files from an older app version, an older style, or the old naming scheme. */
    fun isStale(fileName: String, pkg: String, generation: String): Boolean =
        fileName.startsWith("$pkg~") && !fileName.startsWith("$pkg~$generation~")

    /**
     * Files to delete after an app scan. [live] maps each installed package to its current generation.
     * Removes apps uninstalled while Aura wasn't running (no PACKAGE_REMOVED reached it), apps whose
     * stale renders were never replaced (e.g. hidden apps after a style bump), and unknown files.
     */
    fun prunable(fileNames: List<String>, live: Map<String, String>): List<String> = fileNames.filter { n ->
        val pkg = n.substringBefore('~')
        val gen = live[pkg]
        gen == null || isStale(n, pkg, gen)
    }

    data class Info(val name: String, val bytes: Long, val modified: Long)

    /** Oldest files to delete so the cache fits in [maxBytes] (a hard bound on top of [prunable]). */
    fun overBudget(files: List<Info>, maxBytes: Long): List<String> {
        var total = files.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.modified }) {
            if (total <= maxBytes) break
            out += f.name
            total -= f.bytes
        }
        return out
    }
}
