package com.auratv.launcher.apps

/** A launchable activity. [key] is "package/class" and is what we persist. */
data class AppEntry(
    val packageName: String,
    val className: String,
    val label: String,
    /** Declares LEANBACK_LAUNCHER, i.e. a real TV app. */
    val leanback: Boolean,
    /** Pre-installed system app (not updated by the user). */
    val system: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
) {
    val key: String get() = "$packageName/$className"

    fun serialize(): String =
        listOf(packageName, className, label.replace('\t', ' ').replace('\n', ' '),
            if (leanback) "1" else "0", if (system) "1" else "0",
            installedAt.toString(), updatedAt.toString()).joinToString("\t")

    companion object {
        fun parse(line: String): AppEntry? {
            val p = line.split('\t')
            if (p.size < 7) return null
            return try {
                AppEntry(p[0], p[1], p[2], p[3] == "1", p[4] == "1", p[5].toLong(), p[6].toLong())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
