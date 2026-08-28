/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyLyricDecoderTest {
    private val track = TrackIdentity(
        id = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
        title = "Look What You Made Me Do",
        artist = "Taylor Swift"
    )

    @Test
    fun lineSyncedUsesNextStartWhenEndIsZeroAndNeverMapsTransliteration() {
        val publication = SpotifyLyricDecoder.decode(
            """
            {
              "lyrics": {
                "syncType": "LINE_SYNCED",
                "provider": "Musixmatch",
                "lines": [
                  {
                    "startTimeMs": "12450",
                    "words": "Look what you made me do",
                    "endTimeMs": "0",
                    "transliteratedWords": "rukku"
                  },
                  {
                    "startTimeMs": 15800,
                    "words": "Look what you made me do",
                    "endTimeMs": 0
                  }
                ]
              }
            }
            """.trimIndent(),
            track
        )
        assertNotNull(publication)
        assertEquals("LINE_SYNCED", publication.syncType)
        assertEquals(2, publication.lines.size)
        assertEquals(12_450L, publication.lines[0].begin)
        assertEquals(15_800L, publication.lines[0].end)
        assertNull(publication.lines[0].secondary)
        assertEquals(15_800L + 5_000L, publication.lines[1].end)
    }

    @Test
    fun skipsBlankWordsAndMergesAdjacentLatinSyllables() {
        val publication = SpotifyLyricDecoder.decode(
            """
            {
              "lyrics": {
                "syncType": "SYLLABLE_SYNCED",
                "lines": [
                  {"startTimeMs": 0, "words": "", "endTimeMs": 0},
                  {
                    "startTimeMs": 1000,
                    "words": "Galway Girl",
                    "endTimeMs": 3000,
                    "syllables": [
                      {"startTimeMs": 1000, "endTimeMs": 1400, "chars": "Gal"},
                      {"startTimeMs": 1400, "endTimeMs": 1800, "chars": "way"},
                      {"startTimeMs": 1800, "endTimeMs": 2400, "chars": " Girl"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
            track
        )
        assertNotNull(publication)
        assertEquals(1, publication.lines.size)
        val words = publication.lines[0].words
        assertNotNull(words)
        assertEquals(2, words.size)
        assertEquals("Galway", words[0].text)
        assertEquals("Girl", words[1].text)
    }

    @Test
    fun emptyOrInvalidBodiesReturnNull() {
        assertNull(SpotifyLyricDecoder.decode("{}", track))
        assertNull(SpotifyLyricDecoder.decode("not-json", track))
        assertNull(
            SpotifyLyricDecoder.decode(
                """{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":0,"words":"","endTimeMs":0}]}}""",
                track
            )
        )
    }

    @Test
    fun lastLineWithoutEndGetsFiveSecondFallback() {
        val line = SpotifyLyricLine(startTimeMs = 10_000L, words = "Only", endTimeMs = 0L)
        val rich = SpotifyLyricDecoder.toRichLine(line, null)
        assertEquals(15_000L, rich?.end)
        assertTrue(rich?.secondary.isNullOrEmpty())
    }
}
