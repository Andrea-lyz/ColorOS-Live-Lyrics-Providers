/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqOfficialLyricInfoEncoderTest {

    @Test
    fun patchesOfficialJsonWithWordTimingAndTranslation() {
        val track = TrackIdentity(id = "314159", title = "Song", artist = "Artist")
        val lines = listOf(
            RichLyricLine(
                begin = 12_340L,
                end = 16_000L,
                duration = 3_660L,
                text = "第一行歌词",
                words = listOf(
                    LyricWord(12_340L, 13_000L, 660L, "第"),
                    LyricWord(13_000L, 14_500L, 1_500L, "一行歌词")
                ),
                secondary = "First line translation"
            )
        )
        val existing =
            """{"id":0,"songId":"314159","lyricType":0,"lyric":"[00:12.34]第一行歌词\r\n","noLyric":false,"transLyric":""}"""
        val encoded = QqOfficialLyricInfoEncoder.encode(
            track = track,
            lines = lines,
            trackGeneration = 3L,
            hostPackage = QqPlayerConstants.HOST_PACKAGE,
            existingLyricInfo = existing
        )
        requireNotNull(encoded)
        assertTrue(encoded.value.contains("\"id\":0"))
        assertTrue(encoded.value.contains("\"songId\":\"314159\""))
        assertTrue(encoded.value.contains("\"source\":\"qqmusic-internal\""))
        assertTrue(encoded.value.contains("\"provider\":\"com.tencent.qqmusic\""))
        assertFalse(encoded.value.contains("lockscreen-lyrics-module"))
        assertFalse(encoded.value.contains("lyricprovider/qq-music"))
        assertTrue(encoded.value.contains("\"sessionGeneration\":3"))
        assertTrue(encoded.rawLyric.contains("<00:12.340>"))
        assertTrue(encoded.translationLyric.contains("First line translation"))
        assertTrue(encoded.value.contains("\"transLyric\""))
        assertTrue(encoded.value.contains("\"translationLyric\""))
        assertTrue(encoded.plainLyric.contains("第一行歌词"))
    }

    @Test
    fun omitsTranslationWhenSecondaryIsMissingOrUnusable() {
        val encoded = QqOfficialLyricInfoEncoder.encode(
            TrackIdentity(id = "1", title = "夜", artist = "Artist"),
            listOf(RichLyricLine(begin = 1_000L, end = 2_000L, duration = 1_000L, text = "夜")),
            1L,
            QqPlayerConstants.HOST_PACKAGE
        )
        requireNotNull(encoded)
        assertFalse(encoded.value.contains("\"translationLyric\""))
        assertFalse(encoded.value.contains("\"transLyric\""))
    }
}
