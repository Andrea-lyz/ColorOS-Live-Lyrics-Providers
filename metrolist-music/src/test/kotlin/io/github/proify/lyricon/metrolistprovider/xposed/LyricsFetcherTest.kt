/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFetcherTest {
    @Test
    fun includesAlbumInBetterLyricsRequestLikeMetrolist() {
        assertEquals(
            "https://lyrics-api.boidu.dev/getLyrics?" +
                "s=Training+Season&a=Dua+Lipa&d=210&al=Radical+Optimism",
            LyricsFetcher.buildBetterLyricsUrl(
                title = "Training Season",
                artist = "Dua Lipa",
                duration = 210,
                album = "Radical Optimism"
            )
        )
    }

    @Test
    fun convertsKuGouKrcToPlainAndWordTimedLrc() {
        val krc = """
            [1000,1000]<0,400,0>Hello <400,600,0>world
            [2500,500]<0,500,0>Again
        """.trimIndent()

        val result = assertNotNullResult(
            LyricsFetcher.convertDecryptedKrcToLyricsResult(krc)
        )

        assertEquals(
            "[00:01.000]Hello world\n[00:02.500]Again",
            result.plainLyric
        )
        assertEquals(
            "[00:01.000]<00:01.000>Hello <00:01.400>world<00:02.000>\n" +
                "[00:02.500]<00:02.500>Again<00:03.000>",
            result.rawLyric
        )
        assertNotEquals(result.plainLyric, result.rawLyric)
    }

    @Test
    fun convertsBetterLyricsTtmlToCanonicalEnhancedLrc() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:10.123" end="00:11.000">
                  <span begin="00:10.123" end="00:10.600">Hello</span> <span begin="00:10.600" end="00:11.000">world</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = assertNotNullTtml(BetterLyricsTtmlParser.parseTTML(ttml))

        assertEquals("[00:10.123]Hello world", result.plainLrc)
        assertEquals(
            "[00:10.123]<00:10.123>Hello <00:10.600>world<00:11.000>",
            result.enhancedLrc
        )
    }

    @Test
    fun keepsTimingForSingleTimedTtmlSpan() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:01.000" end="00:02.000">
                  <span begin="00:01.000" end="00:02.000">One phrase</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = assertNotNullTtml(BetterLyricsTtmlParser.parseTTML(ttml))

        assertTrue(result.enhancedLrc.contains("<00:01.000>One phrase<00:02.000>"))
    }

    @Test
    fun acceptsTtmlParameterTimingAndClockUnits() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttp="http://www.w3.org/ns/ttml#parameter">
              <body><div>
                <p ttp:begin="1.5s"><span begin="1500ms" end="2.0s">Timed</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = assertNotNullTtml(BetterLyricsTtmlParser.parseTTML(ttml))

        assertEquals("[00:01.500]<00:01.500>Timed<00:02.000>", result.enhancedLrc)
    }

    @Test
    fun mergesAdjacentLatinTtmlSyllableSpansIntoOneTimedWord() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:01.000">
                  <span begin="00:01.000" end="00:01.250">stan</span><span begin="00:01.250" end="00:01.500">ding</span> <span begin="00:01.500" end="00:02.000">there</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = assertNotNullTtml(BetterLyricsTtmlParser.parseTTML(ttml))

        assertEquals("[00:01.000]standing there", result.plainLrc)
        assertEquals(
            "[00:01.000]<00:01.000>standing <00:01.500>there<00:02.000>",
            result.enhancedLrc
        )
    }

    @Test
    fun keepsAdjacentCjkTtmlSpansIndividuallyTimed() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:01.000">
                  <span begin="00:01.000" end="00:01.500">你</span><span begin="00:01.500" end="00:02.000">好</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = assertNotNullTtml(BetterLyricsTtmlParser.parseTTML(ttml))

        assertEquals("[00:01.000]你好", result.plainLrc)
        assertEquals(
            "[00:01.000]<00:01.000>你<00:01.500>好<00:02.000>",
            result.enhancedLrc
        )
    }

    @Test
    fun appliesBetterLyricsAudioOffsetToLineAndWordTiming() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <head><metadata><audio lyricOffset="0.125"/></metadata></head>
              <body><div>
                <p begin="00:01.000">
                  <span begin="00:01.000" end="00:02.000">Offset</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = assertNotNullTtml(BetterLyricsTtmlParser.parseTTML(ttml))

        assertEquals("[00:01.125]<00:01.125>Offset<00:02.125>", result.enhancedLrc)
    }

    private fun assertNotNullResult(
        value: LyricsFetcher.LyricsResult?
    ): LyricsFetcher.LyricsResult {
        assertNotNull(value)
        return value!!
    }

    private fun assertNotNullTtml(
        value: BetterLyricsTtmlParser.TTMLResult?
    ): BetterLyricsTtmlParser.TTMLResult {
        assertNotNull(value)
        return value!!
    }

}
