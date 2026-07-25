/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineTruncatorTest {

    @Test
    fun byteBudgetReservesHeadroomAndClampsToZero() {
        val cap = 512 * 1024
        val budget = LyricLineTruncator.byteBudget(cap)
        assertTrue(
            "byte budget should leave room for metadata",
            budget < cap - LyricLineTruncator.METADATA_HEADROOM_BYTES
        )
        assertTrue(
            "byte budget must always remain positive for the standard cap",
            budget > cap / 2
        )
        assertEquals(0, LyricLineTruncator.byteBudget(0))
        assertEquals(0, LyricLineTruncator.byteBudget(-1))
        // A cap that is smaller than the reserved headroom must collapse to 0.
        assertEquals(
            0,
            LyricLineTruncator.byteBudget(LyricLineTruncator.METADATA_HEADROOM_BYTES - 1)
        )
    }

    @Test
    fun truncatedTextFitsInsideBudget() {
        val lines = buildLongLyric(4000, perLineBytes = 96)
        val original = lines.joinToString(separator = "\n", postfix = "\n")
        val maxBytes = 8 * 1024
        val result = LyricLineTruncator.truncateByLines(original, maxBytes)
        assertTrue(
            "truncated payload must fit inside the byte budget",
            result.text.toByteArray(Charsets.UTF_8).size <= maxBytes
        )
        assertTrue("must remove at least some lines", result.removedLines > 0)
    }

    @Test
    fun truncationPreservesHeadAndTailCues() {
        val lines = (1..200).map { "[00:0${it / 60}.${it % 60}]line-$it" }
        val original = lines.joinToString(separator = "\n", postfix = "\n")
        val maxBytes = 2 * 1024
        val result = LyricLineTruncator.truncateByLines(original, maxBytes)
        assertTrue(
            "opening cue must be preserved",
            result.text.startsWith("[00:00.1]")
        )
        assertTrue(
            "chorus tail must be preserved",
            result.text.contains("line-200")
        )
        assertTrue(result.removedLines > 0)
        assertTrue(
            "truncated payload must fit inside the byte budget",
            result.text.toByteArray(Charsets.UTF_8).size <= maxBytes
        )
    }

    @Test
    fun emptyOrTinyInputIsReturnedUnchanged() {
        assertEquals("", LyricLineTruncator.truncateByLines("", 1024).text)
        assertFalse(LyricLineTruncator.truncateByLines("", 1024).changed)
        val tiny = "[00:00.00]hi"
        val result = LyricLineTruncator.truncateByLines(tiny, 1024)
        assertEquals(tiny, result.text)
        assertFalse(result.changed)
        assertEquals(0, result.removedLines)
    }

    @Test
    fun zeroOrNegativeBudgetNeverThrows() {
        val lines = buildLongLyric(50, perLineBytes = 64)
        val original = lines.joinToString(separator = "\n", postfix = "\n")
        val zero = LyricLineTruncator.truncateByLines(original, 0)
        val negative = LyricLineTruncator.truncateByLines(original, -10)
        assertEquals("", zero.text)
        assertEquals("", negative.text)
        assertTrue(zero.changed)
        assertTrue(negative.changed)
    }

    @Test
    fun twoLinePayloadStillFitsTheBudget() {
        val original = "[00:00.00]alpha\n[00:01.00]beta\n"
        val result = LyricLineTruncator.truncateByLines(original, 1)
        assertTrue(result.changed)
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 1)
        assertTrue(original.startsWith(result.text))
    }

    @Test
    fun absurdlySmallBudgetFallsBackToFirstLine() {
        val original = "[00:00.00]head\n[00:01.00]second\n[00:02.00]third\n[00:03.00]tail\n"
        // 8 bytes cannot hold a full line, but the partial opening cue must
        // remain valid UTF-8 and remain inside the hard byte budget.
        val result = LyricLineTruncator.truncateByLines(original, 8)
        assertTrue(original.startsWith(result.text))
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 8)
        assertTrue(result.removedLines >= 1)
    }

    @Test
    fun truncatePayloadShrinksTranslationFirst() {
        val raw = "[00:00.00]raw timing\n"
        val lyric = "[00:00.00]plain lyric\n"
        val translation = "[00:00.00]translation\n"
        val maxBytes = raw.toByteArray(Charsets.UTF_8).size + lyric.toByteArray(Charsets.UTF_8).size
        val result = LyricLineTruncator.truncatePayload(
            lyric = lyric,
            rawLyric = raw,
            translationLyric = translation,
            maxBytes = maxBytes
        )
        val totalBytes = result.lyric.text.toByteArray(Charsets.UTF_8).size +
            result.rawLyric.text.toByteArray(Charsets.UTF_8).size +
            result.translationLyric.text.toByteArray(Charsets.UTF_8).size
        assertTrue("payload must fit inside the byte budget", totalBytes <= maxBytes)
        assertEquals(raw, result.rawLyric.text)
        assertEquals(lyric, result.lyric.text)
        assertEquals("", result.translationLyric.text)
        assertTrue(result.translationLyric.changed)
    }

    @Test
    fun payloadNeverExceedsBudgetForAnOversizedUtf8Line() {
        val oversized = "\u754c".repeat(100)
        val result = LyricLineTruncator.truncatePayload(
            lyric = oversized,
            rawLyric = oversized,
            translationLyric = oversized,
            maxBytes = 17
        )
        val totalBytes = result.lyric.text.toByteArray(Charsets.UTF_8).size +
            result.rawLyric.text.toByteArray(Charsets.UTF_8).size +
            result.translationLyric.text.toByteArray(Charsets.UTF_8).size
        assertTrue(totalBytes <= 17)
        assertTrue(result.rawLyric.text.isNotEmpty())
        assertEquals(0, result.rawLyric.text.toByteArray(Charsets.UTF_8).size % 3)
    }

    @Test
    fun truncatePayloadWithoutTranslationLeavesItEmpty() {
        val lyric = buildLongLyric(80, perLineBytes = 48)
        val raw = buildLongLyric(200, perLineBytes = 64)
        val result = LyricLineTruncator.truncatePayload(
            lyric = lyric.joinToString("\n", postfix = "\n"),
            rawLyric = raw.joinToString("\n", postfix = "\n"),
            translationLyric = "",
            maxBytes = 4 * 1024
        )
        assertEquals("", result.translationLyric.text)
        assertFalse(result.translationLyric.changed)
        assertTrue(result.changed)
    }

    @Test
    fun unchangedPayloadReportsZeroRemovedLines() {
        val lyric = "[00:00.00]line-1\n[00:01.00]line-2\n"
        val result = LyricLineTruncator.truncatePayload(
            lyric = lyric,
            rawLyric = lyric,
            translationLyric = lyric,
            maxBytes = 16 * 1024
        )
        assertFalse(result.changed)
        assertEquals(0, result.lyric.removedLines)
        assertEquals(0, result.rawLyric.removedLines)
        assertEquals(0, result.translationLyric.removedLines)
    }

    private fun buildLongLyric(lineCount: Int, perLineBytes: Int): List<String> {
        val padding = "x".repeat(perLineBytes.coerceAtLeast(12) - 12)
        return (1..lineCount).map { index ->
            val stamp = "[%02d:%02d.00]".format(index / 60, index % 60)
            stamp + "L$index-$padding"
        }
    }
}
