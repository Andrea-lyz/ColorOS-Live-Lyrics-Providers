package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricPayloadPolicy
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtmlLyricsTest {
    /** Better Lyrics / Apple shape: bare-second times, prefixed line keys, head translations. */
    private val duet = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
          <head><metadata>
            <ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/>
            <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
              <translations>
                <translation type="replacement" xml:lang="zh-Hans"><text for="L1">替换</text></translation>
                <translation type="subtitle" xml:lang="ja"><text for="L1">日本語</text></translation>
                <translation type="subtitle" xml:lang="zh-Hant"><text for="L1">繁體一</text><text for="L2">繁體二</text></translation>
                <translation type="subtitle" xml:lang="zh-Hans"><text for="L1">第一行</text><text for="L2">第二行</text></translation>
              </translations>
            </iTunesMetadata>
          </metadata></head>
          <body><div>
            <p begin="3.432" end="6.045" itunes:key="L1" ttm:agent="v1"><span begin="3.432" end="4.763">Ooh,</span> <span begin="4.769" end="6.045">ooh</span></p>
            <p begin="1:09.147" end="1:15.034" itunes:key="L2" ttm:agent="v2"><span begin="1:09.147" end="1:10.363">I</span> <span begin="1:11.520" end="1:15.034">woke</span><span ttm:role="x-bg"><span begin="1:12" end="1:13">(yeah)</span></span></p>
          </div></body>
        </tt>
    """.trimIndent()

    private val query = TrackQuery("Song", "Artist")

    @Test
    fun `document becomes timed lanes plus a translation-free ttml lane`() {
        val result = assertNotNull(TtmlLyrics.toResult(duet, query, "universal/apple-music", "1"))

        assertTrue(result.lyric.lines().first().endsWith("Ooh, ooh"))
        assertTrue(WordTimedLyricConverter.hasRealWordTiming(result.rawLyric.orEmpty()))
        val lane = assertNotNull(result.ttml)
        assertFalse(lane.contains("translation"))
        assertTrue(lane.contains("ttm:agent=\"v2\""))
        assertTrue(lane.contains("x-bg"))
        assertFalse(Regex(""">\s*\n\s*<""").containsMatchIn(lane))
    }

    @Test
    fun `head translation follows the device script and skips replacements`() {
        assertEquals(
            "[00:03.432]第一行\n[01:09.147]第二行",
            TtmlLyrics.translationLrc(duet, Locale.SIMPLIFIED_CHINESE)
        )
        assertEquals(
            "[00:03.432]繁體一\n[01:09.147]繁體二",
            TtmlLyrics.translationLrc(duet, Locale.TRADITIONAL_CHINESE)
        )
        assertEquals("[00:03.432]日本語", TtmlLyrics.translationLrc(duet, Locale.JAPANESE))
        assertNull(TtmlLyrics.translationLrc(duet, Locale.GERMAN))
    }

    @Test
    fun `inline translation spans are read and stripped from the lane`() {
        val amll = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>""" +
            """<p begin="00:01.000" end="00:02.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">Hi</span>""" +
            """<span ttm:role="x-translation" xml:lang="zh-CN">你好</span><span ttm:role="x-roman">hai</span></p>""" +
            """</div></body></tt>"""

        assertEquals("[00:01.000]你好", TtmlLyrics.translationLrc(amll, Locale.SIMPLIFIED_CHINESE))
        val lane = assertNotNull(TtmlLyrics.lane(amll))
        assertFalse(lane.contains("你好"))
        assertFalse(lane.contains("hai"))
    }

    @Test
    fun `oversized or offset documents get no lane`() {
        val offset = duet.replace("<head>", "<head><metadata><audio lyricOffset=\"200\"/></metadata>")
        assertNull(TtmlLyrics.lane(offset))
        val huge = duet.replace("<body>", "<body>" + "<!--" + "x".repeat(TtmlLyrics.MAX_LANE_CHARS) + "-->")
        assertNull(TtmlLyrics.lane(huge))
        assertNull(TtmlLyrics.toResult("[00:01.00]plain lrc", query, "s", null))
    }

    @Test
    fun `ttml leaves the payload together with rawLyric`() {
        val json = JSONObject().apply {
            put("lyric", "[00:01.000]Hi")
            put("rawLyric", "[00:01.000]<00:01.000>Hi<00:02.000>")
            put(UniversalLyricPayloadPolicy.TTML_KEY, "<tt/>")
        }
        val kept = JSONObject(json.toString())
        UniversalLyricPayloadPolicy.apply(kept, wordTiming = false, rawLyric = true, translation = false)
        assertTrue(kept.has(UniversalLyricPayloadPolicy.TTML_KEY))
        val dropped = JSONObject(json.toString())
        UniversalLyricPayloadPolicy.apply(dropped, wordTiming = true, rawLyric = false, translation = true)
        assertFalse(dropped.has(UniversalLyricPayloadPolicy.TTML_KEY))
    }

    /**
     * Real document check (kept outside the repository): set CLL_TTML_TEST_FILE, and optionally
     * CLL_LYRICINFO_OUT to write the lyricInfo the Universal Provider would publish for it.
     */
    @Test
    fun `external document produces a publishable payload`() {
        val path = System.getenv("CLL_TTML_TEST_FILE")?.takeIf { it.isNotBlank() } ?: return
        val result = assertNotNull(TtmlLyrics.toResult(File(path).readText(), query, "universal/apple-music", null))
        val json = JSONObject().apply {
            put("lyric", result.lyric)
            put("rawLyric", result.rawLyric)
            result.translationLyric?.let { put("translationLyric", it) }
            result.ttml?.let { put(UniversalLyricPayloadPolicy.TTML_KEY, it) }
        }
        UniversalLyricPayloadPolicy.apply(json, wordTiming = true, rawLyric = true, translation = true)
        assertTrue(json.has(UniversalLyricPayloadPolicy.TTML_KEY))
        println("universal-ttml lines=" + result.lyric.lines().size + " laneChars=" + result.ttml?.length)
        System.getenv("CLL_LYRICINFO_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(json.toString()) }
    }
}
