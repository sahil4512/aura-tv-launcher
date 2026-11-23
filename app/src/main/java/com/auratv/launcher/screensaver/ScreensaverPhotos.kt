package com.auratv.launcher.screensaver

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.auratv.launcher.settings.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The screensaver's photo cache: today's (and recent days') photos from the chosen feed, kept in
 * app storage so the screensaver starts instantly and still works offline. Refreshed at most every
 * [REFRESH_MS], off the UI thread. This is the only place Aura uses the network.
 */
object ScreensaverPhotos {

    const val SOURCE_BING = 0
    const val SOURCE_WIKIPEDIA = 1
    const val SOURCE_NASA = 2
    const val SOURCE_AURA = 3
    val SOURCE_NAMES = listOf("Bing photo of the day", "Wikipedia picture of the day", "NASA picture of the day", "GlowHome wallpapers (offline)")

    data class Cached(val file: File, val title: String, val credit: String)

    private const val TAG = "AuraScreensaver"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val MAX_PHOTOS = 8
    private const val MAX_BYTES = 6 * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-photos").apply { priority = Thread.MIN_PRIORITY } }
    private val running = AtomicBoolean(false)

    private fun dir(ctx: Context) = File(ctx.filesDir, "screensaver").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.json")

    /** True when the TV's screensaver setting points at Aura's (otherwise there is nothing to fetch). */
    fun isChosenScreensaver(ctx: Context): Boolean = try {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "screensaver_components").orEmpty()
            .contains(ctx.packageName)
    } catch (_: Exception) { false }

    /** Photos on disk for the current source, newest first. */
    fun cached(ctx: Context): List<Cached> = try {
        val arr = JSONArray(index(ctx).readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            File(dir(ctx), o.getString("file")).takeIf { it.isFile }?.let { Cached(it, o.optString("title"), o.optString("credit")) }
        }
    } catch (_: Exception) { emptyList() }

    /** Fetches new photos if the cache is old or was made for another source. Safe to call often. */
    fun refreshIfStale(ctx: Context, prefs: Prefs, force: Boolean = false, done: (() -> Unit)? = null) {
        val source = prefs.screensaverSource
        if (source == SOURCE_AURA) { done?.invoke(); return }
        val fresh = System.currentTimeMillis() - prefs.screensaverFetchedAt < REFRESH_MS && prefs.screensaverFetchedSource == source
        if (!force && fresh && cached(ctx).isNotEmpty()) { done?.invoke(); return }
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        io.execute {
            try {
                val photos = when (source) {
                    SOURCE_WIKIPEDIA -> {
                        // One request per day; the feed is dated in UTC.
                        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        (0 until 10).mapNotNull { back ->
                            val d = fmt.format(Date(System.currentTimeMillis() - back * 86_400_000L))
                            try { PhotoFeeds.parseWikipedia(get(PhotoFeeds.wikipediaUrl(d)), d) } catch (e: Exception) {
                                Log.w(TAG, "wikipedia $d: ${e.javaClass.simpleName}: ${e.message}"); null
                            }
                        }
                    }
                    SOURCE_NASA -> {
                        val start = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 9 * 86_400_000L))
                        PhotoFeeds.parseNasa(get(PhotoFeeds.nasaUrl(start)))
                    }
                    else -> PhotoFeeds.parseBing(get(PhotoFeeds.bingUrl(MAX_PHOTOS)))
                }.take(MAX_PHOTOS)
                val d = dir(app)
                val out = JSONArray()
                val keep = HashSet<String>()
                for (p in photos) {
                    val name = "s${source}_" + p.url.hashCode().toUInt().toString(16) + ".jpg"
                    val f = File(d, name)
                    if (!f.isFile && !download(p.url, f)) continue
                    keep += name
                    out.put(JSONObject().put("file", name).put("title", p.title).put("credit", p.credit))
                }
                if (out.length() > 0) {
                    index(app).writeText(out.toString())
                    d.listFiles()?.forEach { if (it.name != "index.json" && it.name !in keep) it.delete() }
                    prefs.screensaverFetchedAt = System.currentTimeMillis()
                    prefs.screensaverFetchedSource = source
                }
                Log.i(TAG, "photos: ${out.length()} from ${SOURCE_NAMES[source]}")
            } catch (e: Exception) {
                Log.w(TAG, "photo refresh failed: ${e.message}") // offline: keep whatever is cached
            } finally {
                running.set(false)
                done?.let { com.auratv.launcher.util.Bg.main.post(it) }
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "AuraTV-Screensaver")
        return c
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Downloads one image (at most [MAX_BYTES]) and keeps it only if it decodes. */
    private fun download(url: String, to: File): Boolean {
        val tmp = File(to.path + ".part")
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return false
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return false
                        out.write(buf, 0, n)
                    }
                }
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth < 320 || o.outHeight < 180) return false
            return tmp.renameTo(to)
        } catch (e: Exception) {
            return false
        } finally {
            tmp.delete()
            c.disconnect()
        }
    }
}
