/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeLyricLinePolicyTest {
    @Test
    fun retainsTimedOpeningCreditsForBridgeCleanup() {
        val lines = listOf(
            RichLyricLine(begin = 1_725L, text = "Produced by: Aaron Dessner"),
            RichLyricLine(begin = 480L, text = "Written by: Taylor Swift / Aaron Dessner"),
            RichLyricLine(begin = 15_222L, text = "I'm doing good, I'm on some new shit")
        )

        val retained = retainBridgeLyricLines(lines)

        assertEquals(3, retained.size)
        assertEquals("Written by: Taylor Swift / Aaron Dessner", retained[0].text)
        assertEquals("Produced by: Aaron Dessner", retained[1].text)
        assertEquals("I'm doing good, I'm on some new shit", retained[2].text)
    }

    @Test
    fun removesOnlyBlankRowsAndSortsByTimestamp() {
        val lines = listOf(
            RichLyricLine(begin = 2_000L, text = "Second lyric"),
            RichLyricLine(begin = 500L, text = "   "),
            RichLyricLine(begin = 1_000L, text = "First lyric")
        )

        val retained = retainBridgeLyricLines(lines)

        assertEquals(listOf("First lyric", "Second lyric"), retained.map { it.text })
    }
}
