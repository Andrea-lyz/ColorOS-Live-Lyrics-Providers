package io.github.proify.lyricon.qishuiprovider.xposed.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvLyricParserTest {

    @Test
    fun parsesStandardPrefixTimedKrc() {
        val line = KtvLyricParser.parse(
            "[1000,1000]<0,200,0>你<200,300,0>好"
        ).single()

        assertEquals("你好", line.text)
        assertEquals(listOf("你", "好"), line.words.orEmpty().map { it.text })
        assertEquals(listOf(1_000L, 1_200L), line.words.orEmpty().map { it.begin })
        assertEquals(2_000L, line.end)
    }

    @Test
    fun keepsEnglishSpacesInsideTimedSegments() {
        val line = KtvLyricParser.parse(
            "[22010,4440]<0,530,0>Every <1160,610,0>night"
        ).single()

        assertEquals("Every night", line.text)
        assertEquals(2, line.words.orEmpty().size)
        assertEquals("Every ", line.words.orEmpty()[0].text)
        assertEquals("night", line.words.orEmpty()[1].text)
    }

    @Test
    fun keepsSupplementaryUnicodeAsOneTimedSegment() {
        val line = KtvLyricParser.parse(
            "[1000,500]<0,500,0>👩‍🚀"
        ).single()

        assertEquals("👩‍🚀", line.text)
        assertEquals(1, line.words.orEmpty().size)
        assertEquals("👩‍🚀", line.words.orEmpty().single().text)
        assertTrue(line.words.orEmpty().single().text.orEmpty().codePointCount(0, "👩‍🚀".length) > 1)
    }

    @Test
    fun supportsLegacySuffixTimedConversionWithoutSplittingCharacters() {
        val line = KtvLyricParser.parse(
            "[1000,600]你<0,300,0>好<300,300,0>"
        ).single()

        assertEquals("你好", line.text)
        assertEquals(listOf("你", "好"), line.words.orEmpty().map { it.text })
        assertEquals(listOf(1_000L, 1_300L), line.words.orEmpty().map { it.begin })
    }

    @Test
    fun expandsLineEndToCoverDeclaredWordEnd() {
        val line = KtvLyricParser.parse(
            "[1000,100]<0,800,0>long"
        ).single()

        assertEquals(1_800L, line.end)
        assertEquals(800L, line.duration)
    }

    @Test
    fun preservesDuplicateLineTimestampsInSourceOrder() {
        val lines = KtvLyricParser.parse(
            "[1000,500]<0,500,0>first\n[1000,500]<0,500,0>second"
        )

        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun clampsBackwardsWordOffsetsWithoutReorderingText() {
        val line = KtvLyricParser.parse(
            "[1000,600]<300,100,0>a<100,100,0>b"
        ).single()

        assertEquals("ab", line.text)
        assertEquals(listOf(1_300L, 1_300L), line.words.orEmpty().map { it.begin })
    }

    @Test
    fun keepsUntimedLineAndIgnoresMalformedInput() {
        val lines = KtvLyricParser.parse(
            "broken\n[1000,500]instrumental punctuation…"
        )

        assertEquals(1, lines.size)
        assertEquals("instrumental punctuation…", lines.single().text)
        assertTrue(lines.single().words.orEmpty().isEmpty())
        assertTrue(KtvLyricParser.parse(null).isEmpty())
    }
}
