/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleSongMapperTest {
    @Test
    fun dropsBackgroundOnlyAndShortBackingVocalLines() {
        val backgroundOnly = AppleLyricLineModel(
            htmlLineText = "oh",
            htmlBackgroundVocalsLineText = "oh",
            words = emptyList(),
            backgroundWords = listOf(AppleLyricWordModel(text = "oh"))
        )
        assertFalse(AppleSongMapper.shouldKeepLeadLyricLine(backgroundOnly))

        val backing = AppleLyricLineModel(
            htmlLineText = "yeah",
            words = listOf(AppleLyricWordModel(text = "yeah")),
            backgroundWords = listOf(AppleLyricWordModel(text = "yeah"))
        )
        assertFalse(AppleSongMapper.shouldKeepLeadLyricLine(backing))

        val lead = AppleLyricLineModel(
            begin = 1000,
            end = 2000,
            htmlLineText = "It's a cruel summer",
            htmlTranslationLineText = "这是一个残酷的夏天",
            words = listOf(
                AppleLyricWordModel(begin = 1000, end = 1400, text = "It's"),
                AppleLyricWordModel(begin = 1400, end = 2000, text = " a cruel summer")
            )
        )
        assertTrue(AppleSongMapper.shouldKeepLeadLyricLine(lead))
        val mapped = AppleSongMapper.toRichLines(AppleSongModel("1", lyrics = listOf(lead)))
        assertEquals(1, mapped.size)
        assertEquals("It's a cruel summer", mapped[0].text)
        assertEquals("这是一个残酷的夏天", mapped[0].secondary)
        assertEquals(2, mapped[0].words?.size)
    }

    @Test
    fun pronunciationNeverBecomesTranslation() {
        val line = AppleLyricLineModel(
            htmlLineText = "夏",
            htmlTranslationLineText = "natsu",
            htmlPronunciationLineText = "natsu"
        )
        assertNull(AppleSongMapper.usableTranslation(line))

        val translated = AppleLyricLineModel(
            htmlLineText = "夏",
            htmlTranslationLineText = "summer",
            htmlPronunciationLineText = "natsu"
        )
        assertEquals("summer", AppleSongMapper.usableTranslation(translated))
        assertNull(
            AppleSongMapper.usableTranslation(
                AppleLyricLineModel(htmlLineText = "hello", htmlTranslationLineText = "hello")
            )
        )
    }

    @Test
    fun whitespaceWordsAreDroppedFromKaraokeLane() {
        val line = AppleLyricLineModel(
            begin = 0,
            end = 800,
            htmlLineText = "Hello world",
            words = listOf(
                AppleLyricWordModel(begin = 0, end = 300, text = "Hello"),
                AppleLyricWordModel(begin = 300, end = 400, text = " ", whitespace = true),
                AppleLyricWordModel(begin = 400, end = 800, text = "world")
            )
        )
        val mapped = AppleSongMapper.toRichLines(AppleSongModel("1", lyrics = listOf(line)))
        assertEquals(listOf("Hello", "world"), mapped[0].words?.map { it.text })
    }

    @Test
    fun mergesUnspacedLatinSyllablesSoBridgeDoesNotInsertASpace() {
        val line = AppleLyricLineModel(
            begin = 0,
            end = 800,
            htmlLineText = "Galway Girl",
            words = listOf(
                AppleLyricWordModel(begin = 0, end = 180, text = "Gal"),
                AppleLyricWordModel(begin = 180, end = 420, text = "way"),
                AppleLyricWordModel(begin = 420, end = 500, text = " ", whitespace = true),
                AppleLyricWordModel(begin = 500, end = 800, text = "Girl")
            )
        )
        val mapped = AppleSongMapper.toRichLines(AppleSongModel("1", lyrics = listOf(line)))
        assertEquals(listOf("Galway", "Girl"), mapped[0].words?.map { it.text })
        assertEquals("Galway Girl", mapped[0].text)

        val leadingSpaceOnNextWord = AppleLyricLineModel(
            begin = 0,
            end = 800,
            htmlLineText = "Hello world",
            words = listOf(
                AppleLyricWordModel(begin = 0, end = 300, text = "Hello"),
                AppleLyricWordModel(begin = 300, end = 800, text = " world")
            )
        )
        val kept = AppleSongMapper.toRichLines(
            AppleSongModel("1", lyrics = listOf(leadingSpaceOnNextWord))
        )
        assertEquals(listOf("Hello", "world"), kept[0].words?.map { it.text })
    }
}
