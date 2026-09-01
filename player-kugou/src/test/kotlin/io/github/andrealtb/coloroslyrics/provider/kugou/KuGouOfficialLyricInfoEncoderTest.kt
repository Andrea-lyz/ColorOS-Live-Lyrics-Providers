/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouOfficialLyricInfoEncoderTest {

    @Test
    fun patchesOfficialJsonWithWordTimingAndTranslation() {
        val track = TrackIdentity(
            id = "7F605167C85BB66AA6E0388144897547",
            title = "Song",
            artist = "Artist"
        )
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
        val existing = """{"id":0,"songId":"7F605167C85BB66AA6E0388144897547","lyricType":0,"lyric":"[00:01.00]歌曲名 - 歌手名\r\n[00:12.34]第一行歌词\r\n","noLyric":false}"""

        val encoded = KuGouOfficialLyricInfoEncoder.encode(
            track = track,
            lines = lines,
            trackGeneration = 3L,
            hostPackage = KuGouPlayerConstants.STANDARD_PACKAGE,
            existingLyricInfo = existing
        )
        requireNotNull(encoded)
        assertTrue(encoded.value.contains("\"id\":0"))
        assertTrue(encoded.value.contains("\"songId\":\"7F605167C85BB66AA6E0388144897547\""))
        assertTrue(encoded.value.contains("\"lyricType\":0"))
        assertTrue(encoded.value.contains("\"source\":\"kugou-internal\""))
        assertTrue(encoded.value.contains("\"provider\":\"com.kugou.android\""))
        assertFalse(encoded.value.contains("lockscreen-lyrics-module"))
        assertFalse(encoded.value.contains("lyricprovider/kugou"))
        assertTrue(encoded.value.contains("\"sessionGeneration\":3"))
        assertTrue(encoded.rawLyric.contains("<00:12.340>"))
        assertTrue(encoded.translationLyric.contains("First line translation"))
        assertTrue(encoded.plainLyric.contains("第一行歌词"))
        assertTrue(encoded.plainLyric.contains("歌曲名 - 歌手名"))
        assertFalse(encoded.rawLyric.contains("歌曲名 - 歌手名"))
    }

    @Test
    fun omitsTranslationWhenSecondaryIsMissingOrUnusable() {
        val track = TrackIdentity(id = "id", title = "夜", artist = "Artist")
        val without = KuGouOfficialLyricInfoEncoder.encode(
            track,
            listOf(RichLyricLine(begin = 1_000L, end = 2_000L, duration = 1_000L, text = "夜")),
            1L,
            KuGouPlayerConstants.LITE_PACKAGE
        )
        requireNotNull(without)
        assertFalse(without.value.contains("\"translationLyric\""))
    }

    @Test
    fun stripsLitePromoAndDoesNotTreatSlashTranslationAsUsable() {
        val track = TrackIdentity(id = "id", title = "Catch Catch", artist = "YENA")
        val lines = listOf(
            RichLyricLine(0L, 500L, 500L, "[听歌就在中国酷狗*星耀计划]"),
            RichLyricLine(1_000L, 2_000L, 1_000L, "catch my heart", secondary = "//")
        )
        val encoded = KuGouOfficialLyricInfoEncoder.encode(
            track,
            lines,
            2L,
            KuGouPlayerConstants.LITE_PACKAGE
        )
        requireNotNull(encoded)
        assertFalse(encoded.rawLyric.contains("星耀计划"))
        assertFalse(encoded.plainLyric.contains("星耀计划"))
        assertFalse(encoded.value.contains("\"translationLyric\""))
        assertTrue(encoded.plainLyric.contains("catch my heart"))
    }

    @Test
    fun replacesNonMonotonicColdStartOfficialLyricWithSanitizedTimeline() {
        val track = TrackIdentity(id = "song-id", title = "回家的路", artist = "HOYO-MiX")
        val lines = listOf(
            RichLyricLine(290L, 377L, 87L, "作词 Lyricist：木木 Woods"),
            RichLyricLine(298L, 388L, 90L, "作曲 Composer：尤裴佳"),
            RichLyricLine(
                670L,
                3_479L,
                2_809L,
                "当你走上回家的路",
                words = listOf(LyricWord(670L, 3_479L, 2_809L, "当你走上回家的路"))
            )
        )
        val existing = """{"id":0,"songId":"song-id","lyricType":0,"lyric":"[00:00.700]回家的路 - HOYO-MiX\n[00:00.290]作词 Lyricist：木木 Woods\n[00:00.298]作曲 Composer：尤裴佳\n[00:00.670]当你走上回家的路\n","noLyric":false}"""

        val encoded = KuGouOfficialLyricInfoEncoder.encode(
            track,
            lines,
            1L,
            KuGouPlayerConstants.STANDARD_PACKAGE,
            existing
        )
        requireNotNull(encoded)

        assertFalse(encoded.plainLyric.contains("[00:00.700]回家的路 - HOYO-MiX"))
        assertTrue(encoded.plainLyric.contains("[00:00.290]作词 Lyricist：木木 Woods"))
        assertTrue(encoded.plainLyric.contains("[00:00.298]作曲 Composer：尤裴佳"))
        assertTrue(encoded.plainLyric.contains("[00:00.670]当你走上回家的路"))
        assertTrue(
            encoded.plainLyric.indexOf("[00:00.290]") <
                encoded.plainLyric.indexOf("[00:00.670]")
        )
    }

    @Test
    fun extractorsReadOfficialStringAndNumericFields() {
        val json = """{"id":0,"songId":"abc","lyric":"line\n"}"""
        assertEquals("abc", KuGouOfficialLyricInfoEncoder.extractJsonString(json, "songId"))
        assertEquals("0", KuGouOfficialLyricInfoEncoder.extractJsonRaw(json, "id"))
        assertEquals("line\n", KuGouOfficialLyricInfoEncoder.extractJsonString(json, "lyric"))
    }
}
