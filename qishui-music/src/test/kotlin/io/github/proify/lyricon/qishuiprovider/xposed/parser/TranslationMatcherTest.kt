package io.github.proify.lyricon.qishuiprovider.xposed.parser

import io.github.proify.lyricon.lyric.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationMatcherTest {

    @Test
    fun acceptsSmallOffsetsBeyondLegacyFixedFiftyMilliseconds() {
        val source = listOf(line(1_000L, "a"), line(2_000L, "b"), line(3_000L, "c"))
        val translated = listOf(
            line(1_080L, "甲"),
            line(2_090L, "乙"),
            line(3_100L, "丙")
        )

        assertEquals(
            listOf("甲", "乙", "丙"),
            matchTranslationLines(source, translated).map { it?.text }
        )
    }

    @Test
    fun matchesDuplicateTimestampsOnlyOnceAndInOrder() {
        val source = listOf(line(1_000L, "a"), line(1_000L, "b"))
        val translated = listOf(line(1_000L, "甲"), line(1_000L, "乙"))

        assertEquals(
            listOf("甲", "乙"),
            matchTranslationLines(source, translated).map { it?.text }
        )
    }

    @Test
    fun rejectsTranslationFromNeighbouringLine() {
        val matched = matchTranslationLines(
            sourceLines = listOf(line(1_000L, "a"), line(2_000L, "b")),
            translationLines = listOf(line(1_700L, "wrong"))
        )

        assertNull(matched.first())
    }

    private fun line(begin: Long, text: String): LyricLine {
        return LyricLine(begin = begin, end = begin + 500L, duration = 500L).also {
            it.text = text
        }
    }
}
