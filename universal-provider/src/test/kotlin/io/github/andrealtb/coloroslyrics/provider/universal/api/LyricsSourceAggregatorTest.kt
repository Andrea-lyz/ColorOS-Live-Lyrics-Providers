package io.github.andrealtb.coloroslyrics.provider.universal.api

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LyricsSourceAggregatorTest {
    private class FakeSource(
        override val name: String,
        private val result: LyricResult?
    ) : LyricsSource {
        var calls = 0
        override fun fetchLyrics(query: TrackQuery): LyricResult? {
            calls++
            return result
        }
    }

    private val query = TrackQuery(title = "晴天", artist = "周杰伦", durationMs = 269_000L)

    private val lineLyric = """
        [00:10.000]故事的小黄花
        [00:14.000]从出生那年就飘着
        [00:18.000]童年的荡秋千
    """.trimIndent()

    private val wordTimedRaw = """
        [00:10.000]<00:10.000>故<00:10.500>事<00:11.000>的<00:11.500>小<00:12.000>黄<00:12.500>花<00:13.000>
        [00:14.000]<00:14.000>从<00:14.500>出<00:15.000>生<00:15.500>那<00:16.000>年<00:16.500>就<00:17.000>飘<00:17.500>着<00:18.000>
        [00:18.000]<00:18.000>童<00:18.500>年<00:19.000>的<00:19.500>荡<00:20.000>秋<00:20.500>千<00:21.000>
    """.trimIndent()

    private val creditsOnlyWordTimed = """
        [00:00.000]词：周杰伦
        [00:02.000]曲：周杰伦
        [00:04.000]纯音乐，请欣赏
    """.trimIndent()

    @Test
    fun respectsPriorityOrderWhenWordTimingPreferenceIsOff() {
        val qq = FakeSource(
            "qq",
            LyricResult("晴天", "周杰伦", lyric = lineLyric, rawLyric = lineLyric, source = "universal/qq")
        )
        val netease = FakeSource(
            "netease",
            LyricResult(
                "晴天",
                "周杰伦",
                lyric = lineLyric,
                rawLyric = wordTimedRaw,
                translationLyric = "[00:10.000]Translated",
                source = "universal/netease"
            )
        )
        val apple = FakeSource("apple", null)
        val aggregator = LyricsSourceAggregator(qqSource = qq, netEaseSource = netease, appleMusicSource = apple)

        val report = aggregator.fetchFirst(
            query,
            priority = listOf(
                LyricsSourceAggregator.SourceId.QQ,
                LyricsSourceAggregator.SourceId.NETEASE,
                LyricsSourceAggregator.SourceId.APPLE_MUSIC
            ),
            preferWordTiming = false
        )

        assertNotNull(report.result)
        assertEquals("universal/qq", report.result.source)
        assertEquals(1, qq.calls)
        assertEquals(0, netease.calls)
    }

    @Test
    fun wordTimedInstrumentalDoesNotShortCircuitHigherPrioritySource() {
        val qq = FakeSource(
            "qq",
            LyricResult(
                "晴天",
                "周杰伦",
                lyric = creditsOnlyWordTimed,
                rawLyric = wordTimedRaw,
                source = "universal/qq"
            )
        )
        val netease = FakeSource(
            "netease",
            LyricResult("晴天", "周杰伦", lyric = lineLyric, rawLyric = lineLyric, source = "universal/netease")
        )
        val apple = FakeSource("apple", null)
        val aggregator = LyricsSourceAggregator(qqSource = qq, netEaseSource = netease, appleMusicSource = apple)

        val report = aggregator.fetchFirst(
            query,
            priority = listOf(
                LyricsSourceAggregator.SourceId.QQ,
                LyricsSourceAggregator.SourceId.NETEASE,
                LyricsSourceAggregator.SourceId.APPLE_MUSIC
            ),
            preferWordTiming = true
        )

        assertNotNull(report.result)
        assertEquals("universal/netease", report.result.source)
        assertEquals(1, qq.calls)
        assertEquals(1, netease.calls)
    }

    @Test
    fun lineFallbackStillHonoursPriorityOverTranslationBonus() {
        val qq = FakeSource(
            "qq",
            LyricResult("晴天", "周杰伦", lyric = lineLyric, rawLyric = lineLyric, source = "universal/qq")
        )
        val netease = FakeSource(
            "netease",
            LyricResult(
                "晴天",
                "周杰伦",
                lyric = lineLyric,
                rawLyric = lineLyric,
                translationLyric = "[00:10.000]Translated",
                source = "universal/netease"
            )
        )
        val apple = FakeSource("apple", null)
        val aggregator = LyricsSourceAggregator(qqSource = qq, netEaseSource = netease, appleMusicSource = apple)

        val report = aggregator.fetchFirst(
            query,
            priority = listOf(
                LyricsSourceAggregator.SourceId.QQ,
                LyricsSourceAggregator.SourceId.NETEASE,
                LyricsSourceAggregator.SourceId.APPLE_MUSIC
            ),
            preferWordTiming = true
        )

        assertNotNull(report.result)
        assertEquals("universal/qq", report.result.source)
    }
}
