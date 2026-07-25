/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimeNormalizerTest {

    @Test
    fun negativeWordTimeClampsToZero() {
        val line = RichLyricLine(
            begin = 30_000L,
            end = 33_500L,
            duration = 3_500L,
            text = "hello",
            words = null
        )
        val fixed = WordTimeNormalizer.toAbsolute(line, -120L)
        assertEquals(0L, fixed)
    }

    @Test
    fun relativeOffsetWithinLeadInIsPromotedToAbsolute() {
        val line = RichLyricLine(
            begin = 30_000L,
            end = 33_500L,
            duration = 3_500L,
            text = "hello",
            words = null
        )
        // wordTime=500 is 29.5s ahead of line.begin, well past MAX_LEAD_IN_MS=250,
        // and within max(duration+2000, 2000) = 5500.
        val fixed = WordTimeNormalizer.toAbsolute(line, 500L)
        assertEquals(30_500L, fixed)
    }

    @Test
    fun absoluteOffsetBeforeLineBeginIsKeptAbsolute() {
        val line = RichLyricLine(
            begin = 30_000L,
            end = 33_500L,
            duration = 3_500L,
            text = "hello",
            words = null
        )
        // wordTime=29_900 is only 100ms ahead of line.begin -> within MAX_LEAD_IN_MS,
        // so the input is preserved as-is.
        val fixed = WordTimeNormalizer.toAbsolute(line, 29_900L)
        assertEquals(29_900L, fixed)
    }

    @Test
    fun offsetBeyondRelativeCeilingIsKeptAbsolute() {
        val line = RichLyricLine(
            begin = 30_000L,
            end = 33_500L,
            duration = 3_500L,
            text = "hello",
            words = null
        )
        // wordTime=10_000 is 20s ahead of line.begin, well beyond max(3.5s+2s, 2s).
        val fixed = WordTimeNormalizer.toAbsolute(line, 10_000L)
        assertEquals(10_000L, fixed)
    }

    @Test
    fun firstLineWithZeroBeginCannotBeRelative() {
        val line = RichLyricLine(
            begin = 0L,
            end = 2_000L,
            duration = 2_000L,
            text = "intro",
            words = null
        )
        // line.begin == 0 disqualifies the relative branch by design; even a
        // suspiciously small wordTime must be returned unchanged.
        val fixed = WordTimeNormalizer.toAbsolute(line, 250L)
        assertEquals(250L, fixed)
    }

    @Test
    fun thresholdsAreExposedForDocumentation() {
        assertEquals(250L, WordTimeNormalizer.MAX_LEAD_IN_MS)
        assertEquals(2_000L, WordTimeNormalizer.MAX_RELATIVE_OFFSET_MS)
        assertTrue(WordTimeNormalizer.MAX_LEAD_IN_MS > 0L)
        assertTrue(WordTimeNormalizer.MAX_RELATIVE_OFFSET_MS > 0L)
    }
}
