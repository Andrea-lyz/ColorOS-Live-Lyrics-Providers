package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricPayloadPolicy
import org.json.JSONObject
import org.junit.Test
import java.util.Base64
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricFeatureTest {
    private val wordLrc = "[00:01.000]<00:01.000>Hello <00:01.500>world<00:02.000>\n[00:03.000]<00:03.000>Bye<00:04.000>"

    @Test
    fun `converted ttml keeps the lines, word timing and spacing on one agent`() {
        val result = LyricResult("T", "A", "[00:01.000]Hello world\n[00:03.000]Bye", wordLrc, source = "universal/qq", format = LyricFormats.QRC)
        val converted = TtmlLyricWriter.withConvertedTtml(result)

        assertTrue(converted.ttmlConverted)
        val lane = assertNotNull(converted.ttml)
        assertTrue(lane.contains("""<span begin="00:01.000" end="00:01.500">Hello</span> <span begin="00:01.500" end="00:02.000">world</span>"""))
        assertTrue(lane.contains("""ttm:agent="v1""""))
        assertFalse(lane.contains("v2"))
        // The converted document reads back to the same lyric.
        val parsed = assertNotNull(TtmlLyrics.toResult(lane, TrackQuery("T", "A"), "s", null))
        assertEquals(listOf("Hello world", "Bye"), parsed.lyric.lines().map { it.substringAfter(']') })
        assertEquals("QRC→TTML", LyricFormats.label(converted.format, converted.ttmlConverted))
    }

    @Test
    fun `delivered ttml and untimed text are left alone`() {
        val delivered = LyricResult("T", "A", "x", source = "s", ttml = "<tt/>", format = LyricFormats.TTML)
        assertEquals(delivered, TtmlLyricWriter.withConvertedTtml(delivered))
        val plain = LyricResult("T", "A", "no timing here", source = "s")
        assertNull(TtmlLyricWriter.withConvertedTtml(plain).ttml)
    }

    @Test
    fun `ttml first asks ttml sources first but keeps disabled ones off`() {
        val calls = mutableListOf<String>()
        fun source(id: String, ttml: Boolean, result: LyricResult?) = object : LyricsSource {
            override val name = id
            override val deliversTtml = ttml
            override fun fetchLyrics(query: TrackQuery): LyricResult? = result.also { calls += id }
        }
        val qq = source("qq", false, LyricResult("T", "A", "[00:01.000]Hello world\n[00:03.000]Bye bye my love\n[00:05.000]See you again", wordLrc, source = "universal/qq", format = LyricFormats.QRC))
        val apple = source("apple", true, null)
        val aggregator = LyricsSourceAggregator(qqSource = qq, netEaseSource = source("ne", false, null), appleMusicSource = apple, kugouSource = source("kg", false, null))
        val ids = listOf(LyricsSourceAggregator.SourceId.QQ, LyricsSourceAggregator.SourceId.APPLE_MUSIC)

        val report = aggregator.fetchFirst(TrackQuery("T", "A"), ids, preferTtml = true)

        assertEquals(listOf("apple", "qq"), calls)
        val result = assertNotNull(report.result)
        assertTrue(result.ttmlConverted)
        calls.clear()
        val plain = aggregator.fetchFirst(TrackQuery("T", "A"), ids)
        assertEquals(listOf("qq"), calls)
        assertNull(plain.result?.ttml)
    }

    @Test
    fun `payload carries the format and the snapshot source shows it`() {
        val json = JSONObject().put("source", "universal/kugou")
        UniversalLyricPayloadPolicy.putTtmlAndFormat(
            json,
            LyricResult("T", "A", "x", source = "s", ttml = "<tt/>", format = LyricFormats.KRC, ttmlConverted = true)
        )
        assertEquals("universal/kugou|KRC→TTML", UniversalLyricPayloadPolicy.sourceWithFormat(json))
        json.put("rawLyric", "")
        UniversalLyricPayloadPolicy.apply(json, wordTiming = true, rawLyric = false, translation = true)
        assertEquals("universal/kugou|KRC", UniversalLyricPayloadPolicy.sourceWithFormat(json))
        assertEquals("none", UniversalLyricPayloadPolicy.sourceWithFormat(null))
    }

    @Test
    fun `storefront follows override, then account, then device region`() {
        assertEquals("jp", AppleMusicAuth(storefrontOverride = "JP", accountStorefront = "cn").storefront(Locale.US))
        assertEquals("cn", AppleMusicAuth(accountStorefront = "cn").storefront(Locale.US))
        assertEquals("tw", AppleMusicAuth().storefront(Locale.TRADITIONAL_CHINESE))
        assertEquals("us", AppleMusicAuth().storefront(Locale.ENGLISH))
        assertEquals("cn", AppleMusicAuth.normalizeStorefront("zh-CN"))
        assertEquals("", AppleMusicAuth.normalizeStorefront("zh-Hans"))
    }

    @Test
    fun `language picks the storefront tag closest to the device`() {
        val hk = AppleMusicAuth(accountStorefront = "hk", storefrontLanguages = listOf("zh-Hant-HK", "en-GB", "zh-Hans-CN"))
        assertEquals("zh-Hans-CN", hk.language(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant-HK", hk.language(Locale.forLanguageTag("zh-HK")))
        assertEquals("en-GB", hk.language(Locale.US))
        assertEquals("zh-Hant-HK", hk.language(Locale.JAPANESE))
        assertEquals("zh-Hans-CN", AppleMusicAuth().language(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant-TW", AppleMusicAuth().language(Locale.TRADITIONAL_CHINESE))
        // An override storefront has no known language list: the device tag is sent.
        assertEquals("ja-JP", hk.copy(storefrontOverride = "jp").language(Locale.JAPAN))
    }

    @Test
    fun `auth survives a json round trip`() {
        val auth = AppleMusicAuth(true, "token", "jp", "cn", listOf("zh-Hans-CN"), "dev", 99L, AppleMusicAuth.STATUS_OK, 5L)
        assertEquals(auth, AppleMusicAuth.fromJson(auth.toJson()))
        assertFalse(auth.copy(status = AppleMusicAuth.STATUS_REJECTED).usable)
        assertFalse(auth.copy(official = false).usable)
        assertFalse(auth.copy(status = AppleMusicAuth.STATUS_NO_SUBSCRIPTION).usable)
    }

    @Test
    fun `webplay developer token is told apart from other jwts`() {
        fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val sig = "s".repeat(30)
        val good = part("""{"alg":"ES256","kid":"WebPlayKid"}""") + "." + part("""{"iss":"AMPWebPlay","iat":1,"exp":2000}""") + "." + sig
        val other = part("""{"alg":"ES256","kid":"Other"}""") + "." + part("""{"iss":"AMPWebPlay","exp":3000}""") + "." + sig
        val expired = part("""{"alg":"ES256","kid":"WebPlayKid"}""") + "." + part("""{"iss":"AMPWebPlay","exp":500}""") + "." + sig
        val script = """var a="$other",b="$expired",c="$good";"""

        assertEquals(good to 2000L, findWebPlayToken(script, nowSeconds = 1000L))
        assertNull(findWebPlayToken("var a=\"$other\"", nowSeconds = 1000L))
    }

    @Test
    fun `official song prefers syllable lyrics in the request language`() {
        val song = JSONObject(
            """{"relationships":{
                "lyrics":{"data":[{"attributes":{"ttml":"<tt>line</tt>"}}]},
                "syllable-lyrics":{"data":[{"attributes":{"ttmlLocalizations":[
                    {"locale":"en","ttml":"<tt>en</tt>"},{"locale":"zh-Hans","ttml":"<tt>zh</tt>"}]}}]}}}"""
        )
        assertEquals("<tt>zh</tt>", officialTtml(song, "zh-Hans-CN"))
        assertEquals("<tt>en</tt>", officialTtml(song, "fr-FR"))
        val lineOnly = JSONObject("""{"relationships":{"lyrics":{"data":[{"attributes":{"ttml":"<tt>line</tt>"}}]}}}""")
        assertEquals("<tt>line</tt>", officialTtml(lineOnly, "en"))
        assertNull(officialTtml(JSONObject("{}"), "en"))
    }
}
