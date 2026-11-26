package com.auratv.launcher.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File names of settings backups: `YYYYMMDD_aura-backup.json`, plus the single file of 0.7.0 and earlier. */
object BackupNames {
    const val SUFFIX = "_aura-backup.json"
    /** Written by 0.7.0 and earlier (one file, always overwritten); still offered for restore. */
    const val LEGACY = "aura-backup.json"
    private val DATED = Regex("""\d{8}_aura-backup\.json""")

    /** Name for a backup made at [now] (local date). A second backup on the same day replaces the first. */
    fun forDate(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now)) + SUFFIX

    fun isBackup(name: String): Boolean = name == LEGACY || DATED.matches(name)

    /** Newest first: dated names by their date (then time), the legacy file by its time among them. */
    fun <T> newestFirst(items: List<T>, name: (T) -> String, time: (T) -> Long): List<T> =
        items.sortedWith(compareByDescending<T> { sortKey(name(it), time(it)) }.thenByDescending { time(it) })

    /** yyyyMMdd as a number (the legacy file: its modification date). */
    private fun sortKey(name: String, time: Long): Long =
        if (DATED.matches(name)) name.substring(0, 8).toLong()
        else SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time)).toLong()
}
