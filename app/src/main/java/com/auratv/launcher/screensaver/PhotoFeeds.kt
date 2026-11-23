package com.auratv.launcher.screensaver

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsers for the daily-photo feeds. Pure (org.json only), so they are unit tested against real
 * responses.
 */
object PhotoFeeds {

    data class Photo(val url: String, val title: String, val credit: String, val date: String)

    /** Bing's homepage archive: the last [n] daily images (newest first), 1920×1080. */
    fun bingUrl(n: Int = 8, market: String = "en-US") =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=$n&mkt=$market"

    fun parseBing(json: String): List<Photo> {
        val images = JSONObject(json).optJSONArray("images") ?: return emptyList()
        val out = ArrayList<Photo>()
        for (i in 0 until images.length()) {
            val o = images.optJSONObject(i) ?: continue
            val path = o.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val url = if (path.startsWith("http")) path else "https://www.bing.com$path"
            // "Lake Bled, Slovenia (© Photographer/Agency)"
            val copyright = o.optString("copyright")
            val cut = copyright.indexOf(" (©")
            val title = o.optString("title").takeIf { it.isNotEmpty() }
                ?: if (cut > 0) copyright.substring(0, cut) else copyright
            val credit = if (cut > 0) copyright.substring(cut + 3).trimEnd(')').trim() else "" // without the "©"
            out += Photo(url, title, credit, o.optString("startdate"))
        }
        return out
    }

    /** Wikipedia's featured picture for one day (UTC date "yyyy/MM/dd"); free, no key, CC-licensed. */
    fun wikipediaUrl(date: String) = "https://en.wikipedia.org/api/rest_v1/feed/featured/$date"

    /**
     * The day's picture, as a 1920-px-wide Wikimedia thumbnail. Portrait pictures are skipped: on a
     * TV they would be cropped to a sliver.
     */
    fun parseWikipedia(json: String, date: String = ""): Photo? {
        val img = JSONObject(json).optJSONObject("image") ?: return null
        val full = img.optJSONObject("image")
        val w = full?.optInt("width") ?: 0
        val h = full?.optInt("height") ?: 0
        if (w <= 0 || h <= 0 || w < h * 1.25) return null
        val thumb = img.optJSONObject("thumbnail")?.optString("source")?.substringBefore('?') ?: return null
        val url = Regex("/\\d+px-").find(thumb)?.let { thumb.replaceRange(it.range, "/1920px-") } ?: return null
        val desc = img.optJSONObject("description")?.optString("text").orEmpty()
        val title = desc.substringBefore(". ").substringBefore(" (").trim().ifEmpty {
            img.optString("title").removePrefix("File:").substringBeforeLast('.')
        }
        // Artist fields are free text (sometimes a whole note); keep the first line, briefly.
        val artist = img.optJSONObject("artist")?.optString("text").orEmpty().replace(Regex("<[^>]*>"), "")
            .lineSequence().firstOrNull().orEmpty().trim().let { if (it.length > 48 || it.startsWith("NOTE", true)) "" else it }
        val license = img.optJSONObject("license")?.optString("type").orEmpty()
        val credit = listOf(artist, license).filter { it.isNotEmpty() }.joinToString(" · ")
        return Photo(url, title, credit, date)
    }

    /** NASA Astronomy Picture of the Day, for the [days] days ending today (DEMO_KEY: 50 calls/day per IP). */
    fun nasaUrl(startDate: String, apiKey: String = "DEMO_KEY") =
        "https://api.nasa.gov/planetary/apod?api_key=$apiKey&start_date=$startDate&thumbs=false"

    fun parseNasa(json: String): List<Photo> {
        val arr = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject(json))
        val out = ArrayList<Photo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("media_type") != "image") continue // some days are videos
            // "url" is a screen-sized image; "hdurl" can be many megabytes.
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: o.optString("hdurl").takeIf { it.startsWith("http") } ?: continue
            val credit = o.optString("copyright").replace('\n', ' ').trim().ifEmpty { "NASA" }
            out += Photo(url, o.optString("title"), credit, o.optString("date"))
        }
        return out.sortedByDescending { it.date }
    }
}
