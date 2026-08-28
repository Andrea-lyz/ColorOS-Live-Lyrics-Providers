/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import io.github.andrealtb.coloroslyrics.provider.parser.yrc.YrcParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricInfoPayloadEncoderTest {

    @Test
    fun patchesOfficialJsonWithWordTimingAndTranslation() {
        val track = TrackIdentity(id = "314159", title = "Song", artist = "Artist", album = "Album")
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
        val existing = """{"lyric":"[00:12.34]第一行歌词\n","songName":"Song","artist":"Artist"}"""
        val encoded = NeteaseLyricInfoPayloadEncoder.encode(
            track = track,
            lines = lines,
            trackGeneration = 3L,
            hostPackage = NeteasePlayerConstants.HOST_PACKAGE,
            existingLyricInfo = existing,
            mode = NeteasePayloadMode.OFFICIAL_APPEND
        )
        requireNotNull(encoded)
        assertTrue(encoded.value.contains("\"songName\":\"Song\""))
        assertTrue(encoded.value.contains("\"source\":\"netease-official-append\""))
        assertTrue(encoded.value.contains("\"songId\":\"314159\""))
        assertTrue(encoded.value.contains("\"sessionGeneration\":3"))
        assertTrue(encoded.value.contains("\"album\":\"Album\""))
        assertFalse(encoded.value.contains("lockscreen-lyrics-module"))
        assertFalse(encoded.value.contains("lyricprovider/netease-cloud-music"))
        assertTrue(encoded.rawLyric.contains("<00:12.340>"))
        assertTrue(encoded.translationLyric.contains("First line translation"))
        assertTrue(encoded.value.contains("\"translationLyric\""))
        assertTrue(encoded.plainLyric.contains("第一行歌词"))
        assertTrue(NeteaseLyricInfoPayloadEncoder.isOfficialAppend(encoded.value))
        assertFalse(NeteaseLyricInfoPayloadEncoder.isOfficialAppend(existing))
        assertFalse(NeteaseLyricInfoPayloadEncoder.isOfficialAppend(null))
    }

    @Test
    fun omitsTranslationWhenSecondaryIsMissing() {
        val encoded = NeteaseLyricInfoPayloadEncoder.encode(
            TrackIdentity(id = "1", title = "夜", artist = "Artist"),
            listOf(RichLyricLine(begin = 1_000L, end = 2_000L, duration = 1_000L, text = "夜")),
            1L,
            NeteasePlayerConstants.HOST_PACKAGE,
            mode = NeteasePayloadMode.OFFICIAL_APPEND
        )
        requireNotNull(encoded)
        assertFalse(encoded.value.contains("\"translationLyric\""))
    }

    @Test
    fun constructsCompletePayloadForModifiedNetease() {
        val encoded = NeteaseLyricInfoPayloadEncoder.encode(
            track = TrackIdentity(id = "314159", title = "Song", artist = "Artist"),
            lines = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 2_000L,
                    duration = 1_000L,
                    text = "Line",
                    secondary = "Translation"
                )
            ),
            trackGeneration = 7L,
            hostPackage = NeteasePlayerConstants.HOST_PACKAGE,
            mode = NeteasePayloadMode.CONSTRUCTED
        )
        requireNotNull(encoded)
        assertTrue(encoded.value.contains("\"source\":\"netease-constructed\""))
        assertTrue(encoded.value.contains("\"lyric\""))
        assertTrue(encoded.value.contains("\"rawLyric\""))
        assertTrue(encoded.value.contains("\"translationLyric\""))
        assertTrue(NeteaseLyricInfoPayloadEncoder.isModulePayload(encoded.value))
        assertFalse(NeteaseLyricInfoPayloadEncoder.isOfficialAppend(encoded.value))
        assertTrue(
            NeteaseLyricInfoPayloadEncoder.isModulePayloadForTrack(
                encoded.value,
                TrackIdentity(id = "314159", title = "Song", artist = "Artist")
            )
        )
        assertFalse(
            NeteaseLyricInfoPayloadEncoder.isModulePayloadForTrack(
                encoded.value,
                TrackIdentity(id = "271828", title = "Song", artist = "Artist")
            )
        )
    }

    @Test
    fun rejectsPreviousTrackModuleAppendOnLoveStoryMetadata() {
        val previous = NeteaseLyricInfoPayloadEncoder.encode(
            TrackIdentity(
                id = "3390083812",
                title = "I Knew It, I Knew You",
                artist = "Cat Burns"
            ),
            listOf(
                RichLyricLine(
                    begin = 6_107L,
                    end = 8_432L,
                    duration = 2_325L,
                    text = "I knew you",
                    secondary = "我那样了解你"
                )
            ),
            9L,
            NeteasePlayerConstants.HOST_PACKAGE,
            mode = NeteasePayloadMode.OFFICIAL_APPEND
        )
        requireNotNull(previous)
        assertTrue(
            NeteaseLyricInfoPayloadEncoder.isAppendForTrack(
                previous.value,
                TrackIdentity(title = "I Knew It, I Knew You", artist = "Cat Burns")
            )
        )
        assertFalse(
            NeteaseLyricInfoPayloadEncoder.isAppendForTrack(
                previous.value,
                TrackIdentity(title = "Love Story", artist = "Taylor Swift")
            )
        )
    }

    @Test
    fun bumpsDuplicateWordStartsSoInlineTagsStayStrictlyIncreasing() {
        val yrc = """
            [5790,2550](5790,420,0)Fever (6210,360,0)dream (6570,390,0)high (6960,180,0)in (7140,90,0)the (7230,480,0)quiet (7710,150,0)of (7860,90,0)the (7950,390,0)night
            [8340,3090](8340,180,0)You (8520,330,0)know (8850,270,0)that (9120,180,0)I (9300,450,0)caught (9750,750,0)it (10500,330,0)（(10830,30,0)Oh (10860,60,0)yeah(10920,0,0), (10920,90,0)you're (11010,90,0)right(11100,0,0), (11190,60,0)I (11250,120,0)want (11370,60,0)it(11430,0,0)）
            [11430,2610](11430,450,0)Bad(11880,0,0), (11880,360,0)bad (12240,300,0)boy(12540,0,0), (12540,360,0)shiny (12900,390,0)toy (13290,210,0)with (13500,60,0)a (13560,480,0)price
        """.trimIndent()
        val lines = YrcParser.parse(yrc)
        val encoded = NeteaseLyricInfoPayloadEncoder.encode(
            TrackIdentity(id = "1", title = "Cruel Summer", artist = "Taylor Swift"),
            lines,
            2L,
            NeteasePlayerConstants.HOST_PACKAGE,
            mode = NeteasePayloadMode.OFFICIAL_APPEND
        )
        requireNotNull(encoded)
        val body = encoded.rawLyric.lineSequence().filter { it.contains('<') }.toList()
        assertTrue(body.size >= 3)
        body.forEach { line ->
            val times = inlineTagTimes(line)
            val wordTimes = if (times.size > 1) times.dropLast(1) else times
            assertTrue("expected word tags in $line, got $wordTimes", wordTimes.size >= 2)
            for (index in 1 until wordTimes.size) {
                assertTrue(
                    "duplicate/non-monotonic <$index> in $line: $wordTimes",
                    wordTimes[index] > wordTimes[index - 1]
                )
            }
        }
        val caught = body[1]
        assertTrue(caught.contains("<00:08.340>You"))
        assertTrue(caught.contains("Oh"))
        assertFalse(caught.contains("<00:10.920>, <00:10.920>"))
        val bad = body[2]
        assertTrue(bad.contains("Bad"))
        assertTrue(bad.contains("<00:11.881>bad") || bad.contains("<00:11.880>bad"))
        assertFalse(bad.contains("<00:11.880>, <00:11.880>"))
    }

    @Test
    fun keepsQuotesAndApostrophesStrictlyIncreasing() {
        val yrc = "[44850,2130](44850,180,0)Said (45030,60,0)\"(45090,270,0)I'm " +
            "(45360,360,0)fine(45720,0,0),(45720,0,0)\" (45780,210,0)but"
        val encoded = NeteaseLyricInfoPayloadEncoder.encode(
            TrackIdentity(id = "1", title = "Cruel Summer", artist = "Taylor Swift"),
            YrcParser.parse(yrc),
            4L,
            NeteasePlayerConstants.HOST_PACKAGE,
            mode = NeteasePayloadMode.OFFICIAL_APPEND
        )
        requireNotNull(encoded)
        val line = encoded.rawLyric.lineSequence().first { it.contains("Said") }
        val times = inlineTagTimes(line)
        val wordTimes = if (times.size > 1) times.dropLast(1) else times
        for (index in 1 until wordTimes.size) {
            assertTrue("non-monotonic $wordTimes in $line", wordTimes[index] > wordTimes[index - 1])
        }
        assertTrue(encoded.value.contains("\"provider\""))
        assertTrue(encoded.value.contains("\"rawLyric\""))
    }

    private fun inlineTagTimes(line: String): List<Long> {
        val regex = Regex("""<(\d{1,3}):(\d{2})\.(\d{1,3})>""")
        return regex.findAll(line).map { match ->
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.toLong()
            }
            minutes * 60_000L + seconds * 1_000L + millis
        }.toList()
    }
}
