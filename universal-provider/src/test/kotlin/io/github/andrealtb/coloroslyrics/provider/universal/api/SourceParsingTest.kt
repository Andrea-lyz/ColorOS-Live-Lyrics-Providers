package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.util.Base64
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceParsingTest {
    @Test
    fun `qq search lists nested versions after their song without duplicates`() {
        val rows = JSONArray(
            """[{"mid":"a","id":1,"title":"Song","grp":[{"mid":"b","id":2,"title":"Song (Live)"},{"mid":"a","id":1}]},
               {"mid":"c","id":3,"title":"Other"}]"""
        )
        val expanded = QqSearchResponseParser.expandVersions(rows)
        assertEquals(listOf("a", "b", "c"), (0 until expanded.length()).map { QqSearchResponseParser.mid(expanded.getJSONObject(it)) })
    }

    @Test
    fun `qq web search rows read like musicu rows`() {
        val root = JSONObject(
            """{"data":{"song":{"list":[{"songid":97773,"songmid":"0039Mn","songname":"晴天",
               "singer":[{"name":"周杰伦"}],"albumname":"叶惠美","albummid":"000MkM","interval":269}]}}}"""
        )
        val row = QqSearchResponseParser.webSongArray(root).getJSONObject(0)
        assertEquals("0039Mn", QqSearchResponseParser.mid(row))
        assertEquals(97773L, QqSearchResponseParser.songId(row))
        assertEquals("晴天", QqSearchResponseParser.title(row))
        assertEquals("周杰伦", QqSearchResponseParser.artist(row))
        assertEquals("叶惠美", QqSearchResponseParser.album(row))
        assertEquals(269_000L, QqSearchResponseParser.durationMs(row))
    }

    @Test
    fun `qrc word without duration lasts until the next word`() {
        val line = StructuredLyrics.parseQrc("[1000,2000]A(1000,0)B(1500,300)C(1900,0)").single()
        assertEquals(listOf(1500L, 1800L, 3000L), line.words.map { it.endMs })
    }

    @Test
    fun `kugou signature matches the client algorithm`() {
        val source = KugouSource(OkHttpClient())
        val query = source.signedQuery(mapOf("b" to "2 x", "a" to "1"))
        // md5("LnT6xpN3khm36zse0QzvmgTZ3waWdRSA" + "a=1b=2 x" + salt)
        val expected = java.security.MessageDigest.getInstance("MD5")
            .digest("LnT6xpN3khm36zse0QzvmgTZ3waWdRSAa=1b=2 xLnT6xpN3khm36zse0QzvmgTZ3waWdRSA".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("a=1&b=2%20x&signature=$expected", query)
    }

    @Test
    fun `kugou translation pairs with every krc row including empty ones`() {
        val language = Base64.getEncoder().encodeToString(
            """{"content":[{"type":0,"lyricContent":[["ro"],["ma"],["ji"]]},
                {"type":1,"lyricContent":[["第一行"],[""],["第三行"]]}]}""".toByteArray()
        )
        val krc = "[language:$language]\n" +
            "[1000,1000]<0,500,0>Hel<500,500,0>lo\n" +
            "[2500,500]\n" +
            "[3000,1000]<0,1000,0>World\n"
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        try {
            val song = KugouSource.Song("1", "h", "T", "A", "", 4000L, "")
            val result = assertNotNull(KugouSource(OkHttpClient()).toResult(krc, TrackQuery("T", "A"), song))
            assertEquals("[00:01.000]Hello\n[00:03.000]World", result.lyric)
            assertTrue(WordTimedLyricConverter.hasRealWordTiming(result.rawLyric.orEmpty()))
            assertEquals("[00:01.000]第一行\n[00:03.000]第三行", result.translationLyric)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `kugou search reads nested versions`() {
        val songs = KugouSearchResponseParser.songs(
            JSONArray(
                """[{"SongName":"晴天","FileHash":"H1","ID":"1","Duration":269,"Singers":[{"name":"周杰伦"}],
                    "Grp":[{"SongName":"晴天 (Live)","FileHash":"H2","ID":"2","Duration":299,"Singers":[{"name":"周杰伦"}]}]}]"""
            )
        )
        assertEquals(listOf("H1", "H2"), songs.map { it.hash })
        assertEquals(269_000L, songs.first().durationMs)
    }

    @Test
    fun `stored orders keep kugou and new installs enable it`() {
        assertEquals(listOf("KUGOU", "QQ"), LyricsSourceConfigStore.decode("""["KUGOU","QQ"]"""))
        assertTrue("KUGOU" in LyricsSourceConfigStore.ALL)
        assertEquals(
            LyricsSourceConfigStore.ALL,
            LyricsSourceAggregator.SourceId.values().map { it.name }.sortedBy { LyricsSourceConfigStore.ALL.indexOf(it) }
        )
    }
}
