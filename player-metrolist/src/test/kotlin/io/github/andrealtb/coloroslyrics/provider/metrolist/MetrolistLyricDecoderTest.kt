/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetrolistLyricDecoderTest {
    @Test
    fun convertsBetterLyricsTtmlToWordTimedLines() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:10.123" end="00:11.000">
                  <span begin="00:10.123" end="00:10.600">Hello</span> <span begin="00:10.600" end="00:11.000">world</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val publication = assertNotNull(MetrolistLyricDecoder.decodeTtml(ttml))
        assertTrue(MetrolistLyricDecoder.looksLikeTtml(ttml))
        assertEquals("Hello world", publication.lines.single().text)
        assertEquals(2, publication.lines.single().words.orEmpty().size)
        assertTrue(publication.rawLyric.contains("<00:10.123>Hello"))
        assertEquals("", publication.translationLyric)
    }

    @Test
    fun betterLyricsPlainLrcPayloadIsNotForcedThroughTtml() {
        assertFalse(MetrolistLyricDecoder.looksLikeTtml("[00:10.00]Hello world"))
        val publication = assertNotNull(
            MetrolistLyricDecoder.decodeBetterLyricsPayload("[00:10.000]Hello world")
        )
        assertEquals("Hello world", publication.lines.single().text)
        assertEquals("BetterLyrics", publication.sourceName)
    }

    @Test
    fun convertsKuGouKrcToPlainAndWordTimedLrc() {
        val krc = """
            [1000,1000]<0,400,0>Hello <400,600,0>world
            [2500,500]<0,500,0>Again
        """.trimIndent()

        val publication = assertNotNull(MetrolistLyricDecoder.decodeDecryptedKrc(krc))
        assertEquals(2, publication.lines.size)
        assertEquals("Hello world", publication.lines[0].text)
        assertEquals(2, publication.lines[0].words.orEmpty().size)
        assertEquals(
            "[00:01.000]<00:01.000>Hello <00:01.400>world<00:02.000>\n" +
                "[00:02.500]<00:02.500>Again<00:03.000>",
            publication.rawLyric
        )
        assertEquals("", publication.translationLyric)
    }

    @Test
    fun untimedTextIsNotPublished() {
        assertNull(MetrolistLyricDecoder.decode("plain lyric"))
        assertFalse(MetrolistLyricDecoder.containsTimedLrc(""))
        assertFalse(MetrolistLyricDecoder.containsTimedLrc(null))
    }

    @Test
    fun sameCapturedIdentityMayBindToFirstMetadata() {
        assertTrue(MetrolistLyricDecoder.matchesTrackIdentity(null, TrackIdentity(id = "next")))
        assertTrue(
            MetrolistLyricDecoder.matchesTrackIdentity(
                TrackIdentity(id = "current"),
                TrackIdentity(id = "current")
            )
        )
        assertFalse(
            MetrolistLyricDecoder.matchesTrackIdentity(
                TrackIdentity(id = "previous"),
                TrackIdentity(id = "next")
            )
        )
    }

    @Test
    fun includesAlbumInBetterLyricsRequestLikeMetrolist() {
        assertEquals(
            "https://lyrics-api.boidu.dev/getLyrics?" +
                "s=Training+Season&a=Dua+Lipa&d=210&al=Radical+Optimism",
            MetrolistLyricsFetcher.buildBetterLyricsUrl(
                title = "Training Season",
                artist = "Dua Lipa",
                duration = 210,
                album = "Radical Optimism"
            )
        )
    }
}

