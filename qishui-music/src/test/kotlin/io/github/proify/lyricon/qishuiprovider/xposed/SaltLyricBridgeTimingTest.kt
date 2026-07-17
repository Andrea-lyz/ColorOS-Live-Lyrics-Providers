package io.github.proify.lyricon.qishuiprovider.xposed

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltLyricBridgeTimingTest {

    @Test
    fun enhancedLrcKeepsCanonicalAbsoluteWordTimes() {
        val line = RichLyricLine(
            begin = 10_000L,
            end = 11_000L,
            duration = 1_000L,
            text = "你好",
            words = listOf(
                LyricWord(begin = 10_000L, end = 10_400L, duration = 400L, text = "你"),
                LyricWord(begin = 10_400L, end = 11_000L, duration = 600L, text = "好")
            )
        )
        val song = Song(id = "track", name = "Title", artist = "Artist", lyrics = listOf(line))

        val encoded = SaltLyricBridge.enhancedLrcForTest(song)

        assertTrue(encoded.contains("[00:10.000]<00:10.000>你<00:10.400>好<00:11.000>"))
    }
}
