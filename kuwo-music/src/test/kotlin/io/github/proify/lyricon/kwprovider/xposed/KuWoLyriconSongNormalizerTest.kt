package io.github.proify.lyricon.kwprovider.xposed

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class KuWoLyriconSongNormalizerTest {
    @Test
    fun fillsZeroDurationLinesWithoutMutatingLockscreenSong() {
        val song = Song(
            id = "track",
            duration = 5_000L,
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 1_000L,
                    text = "word timed",
                    words = listOf(
                        LyricWord(begin = 1_000L, end = 1_800L, text = "word timed")
                    )
                ),
                RichLyricLine(
                    begin = 2_000L,
                    end = 2_000L,
                    text = "line timed",
                    translation = "translation"
                ),
                RichLyricLine(
                    begin = 3_000L,
                    end = 3_000L,
                    text = "last"
                )
            )
        )

        val normalized = KuWoLyriconSongNormalizer.normalize(song)

        assertEquals(1_000L, song.lyrics.orEmpty()[0].end)
        assertEquals(1_800L, normalized.lyrics.orEmpty()[0].end)
        assertEquals(800L, normalized.lyrics.orEmpty()[0].duration)
        assertEquals(3_000L, normalized.lyrics.orEmpty()[1].end)
        assertEquals(1_000L, normalized.lyrics.orEmpty()[1].duration)
        assertEquals("translation", normalized.lyrics.orEmpty()[1].translation)
        assertEquals(5_000L, normalized.lyrics.orEmpty()[2].end)
        assertEquals(2_000L, normalized.lyrics.orEmpty()[2].duration)
    }

    @Test
    fun preservesAlreadyValidLineTiming() {
        val song = Song(
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 2_500L,
                    duration = 1_500L,
                    text = "valid"
                )
            )
        )

        val normalized = KuWoLyriconSongNormalizer.normalize(song)

        assertEquals(2_500L, normalized.lyrics.orEmpty()[0].end)
        assertEquals(1_500L, normalized.lyrics.orEmpty()[0].duration)
    }
}
