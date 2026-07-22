/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit

import io.github.proify.qrckit.model.ParsedLyric
import kotlin.test.Test
import kotlin.test.assertEquals

class ParsedLyricTranslationAlignmentTest {

    @Test
    fun matchesDriftedQrcTranslationsInTimelineOrder() {
        val lyric = ParsedLyric(
            lyricsRaw = qrc(1_000L, 3_000L, 5_000L, 7_000L),
            translationRaw = """
                [00:00.780]translation-one
                [00:02.790]translation-two
                [00:04.810]translation-three
                [00:06.800]translation-four
            """.trimIndent()
        )

        assertEquals(
            listOf("translation-one", "translation-two", "translation-three", "translation-four"),
            lyric.richLyricLines.map { it.translation }
        )
    }

    @Test
    fun doesNotReuseOneTranslationForAdjacentQrcLines() {
        val lyric = ParsedLyric(
            lyricsRaw = qrc(10_000L, 10_060L),
            translationRaw = "[00:10.030]shared-translation"
        )

        assertEquals(1, lyric.richLyricLines.count { it.translation == "shared-translation" })
    }

    @Test
    fun preservesQqNoTranslationMarkerAfterAlignment() {
        val lyric = ParsedLyric(
            lyricsRaw = qrc(1_000L, 3_000L),
            translationRaw = """
                [00:00.800]translation-one
                [00:02.800]//
            """.trimIndent()
        )

        assertEquals(listOf("translation-one", null), lyric.richLyricLines.map { it.translation })
    }

    @Test
    fun doesNotBridgeAnUnrelatedTranslationAcrossTheTimeline() {
        val lyric = ParsedLyric(
            lyricsRaw = qrc(1_000L, 3_000L),
            translationRaw = "[00:06.000]unrelated-translation"
        )

        assertEquals(listOf(null, null), lyric.richLyricLines.map { it.translation })
    }

    private fun qrc(vararg begins: Long): String {
        val content = begins.joinToString(separator = "") { begin ->
            "[$begin,500]main-$begin($begin,500)"
        }
        return "<Lyric LyricContent=\"$content\"/>"
    }
}
