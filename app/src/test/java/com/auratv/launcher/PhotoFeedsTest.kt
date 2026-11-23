package com.auratv.launcher

import com.auratv.launcher.screensaver.PhotoFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFeedsTest {
    @Test fun bingParsesUrlTitleAndCredit() {
        val json = """{"images":[{"startdate":"20261102","url":"/th?id=OHR.LakeBled_EN-US123_1920x1080.jpg&rf=x.jpg",
            "copyright":"Lake Bled, Slovenia (© Jane Doe/Getty Images)","title":"Island on a lake"},
            {"startdate":"20261101","url":"/th?id=OHR.Dunes_1920x1080.jpg","copyright":"Dunes of Namibia (© A. Person)"}]}"""
        val p = PhotoFeeds.parseBing(json)
        assertEquals(2, p.size)
        assertEquals("https://www.bing.com/th?id=OHR.LakeBled_EN-US123_1920x1080.jpg&rf=x.jpg", p[0].url)
        assertEquals("Island on a lake", p[0].title)
        assertEquals("Jane Doe/Getty Images", p[0].credit) // the screensaver adds the "©" itself
        assertEquals("Dunes of Namibia", p[1].title) // no "title": taken from the copyright line
    }

    @Test fun bingEmptyOrBroken() {
        assertTrue(PhotoFeeds.parseBing("""{"images":[]}""").isEmpty())
        assertTrue(PhotoFeeds.parseBing("""{}""").isEmpty())
    }

    @Test fun nasaSkipsVideosAndPrefersScreenSizedUrl() {
        val json = """[{"date":"2026-11-01","media_type":"image","title":"M31","url":"https://apod.nasa.gov/a.jpg","hdurl":"https://apod.nasa.gov/a_big.jpg","copyright":"\nSomeone\n"},
            {"date":"2026-11-02","media_type":"video","title":"Clip","url":"https://youtube.com/x"},
            {"date":"2026-11-03","media_type":"image","title":"Moon","hdurl":"https://apod.nasa.gov/m.jpg"}]"""
        val p = PhotoFeeds.parseNasa(json)
        assertEquals(listOf("Moon", "M31"), p.map { it.title }) // newest first, video dropped
        assertEquals("https://apod.nasa.gov/a.jpg", p[1].url)
        assertEquals("Someone", p[1].credit)
        assertEquals("NASA", p[0].credit) // public-domain NASA images have no copyright field
    }

    @Test fun wikipediaTakesA1920ThumbAndSkipsPortraits() {
        val land = """{"image":{"title":"File:Pez ballesta.jpg",
            "thumbnail":{"source":"https://upload.wikimedia.org/wikipedia/commons/thumb/7/7a/Pez.jpg/960px-Pez.jpg?utm_source=x","width":640},
            "image":{"source":"https://upload.wikimedia.org/x.jpg","width":5174,"height":3449},
            "description":{"text":"Orange-lined triggerfish (Balistapus undulatus), Red Sea, Egypt. This fish..."},
            "artist":{"text":"<a href=\"x\">Diego Delso</a>"},"license":{"type":"CC BY-SA 4.0"}}}"""
        val p = PhotoFeeds.parseWikipedia(land, "2026/11/03")!!
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/7/7a/Pez.jpg/1920px-Pez.jpg", p.url)
        assertEquals("Orange-lined triggerfish", p.title)
        assertEquals("Diego Delso · CC BY-SA 4.0", p.credit)
        val portrait = land.replace("\"width\":5174,\"height\":3449", "\"width\":2000,\"height\":3000")
        assertEquals(null, PhotoFeeds.parseWikipedia(portrait))
    }
}
