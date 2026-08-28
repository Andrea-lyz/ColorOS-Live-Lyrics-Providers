/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuWoLrcxParserTest {

    @Test
    fun parseSimpleLrcxLineWithWordTags() {
        val raw = """
            [ti:test]
            [ar:artist]
            [kuwo:21]
            [00:01.00]<400,200>你<800,200>好<1200,200>世
        """.trimIndent()

        val lines = KuWoLrcxParser.parse(raw)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("你好世", line.text)
        assertEquals(1000L, line.begin)
        assertEquals(3, line.words?.size)
    }

    @Test
    fun stripsWordTagsFromDisplayText() {
        val raw = "[00:05.50]<0,100>测<100,100>试"
        val lines = KuWoLrcxParser.parse(raw)
        assertEquals(1, lines.size)
        assertEquals("测试", lines[0].text)
    }

    @Test
    fun handlesMultipleTimedLinesSorted() {
        val raw = """
            [00:10.00]<0,50>第
            [00:01.00]<0,50>第
        """.trimIndent()
        val lines = KuWoLrcxParser.parse(raw)
        assertEquals(2, lines.size)
        assertTrue(lines[0].begin <= lines[1].begin)
    }

    @Test
    fun returnsEmptyForBlankInput() {
        assertTrue(KuWoLrcxParser.parse(null).isEmpty())
        assertTrue(KuWoLrcxParser.parse("").isEmpty())
        assertTrue(KuWoLrcxParser.parse("   ").isEmpty())
    }

    @Test
    fun parsesMillisecondPrecisionLineTime() {
        val raw = "[00:01.25]<0,100>字"
        val lines = KuWoLrcxParser.parse(raw)
        assertEquals(1, lines.size)
        assertEquals(1250L, lines[0].begin)
    }

    @Test
    fun downgradesAllInvalidWordSpansToLineTimingAndClampsLineEnd() {
        val raw = """
            [kuwo:21]
            [00:00.000]<0,1000>first
            [00:02.000]<0,1000>second
        """.trimIndent()

        val lines = KuWoLrcxParser.parse(raw)

        assertEquals(2, lines.size)
        assertEquals(null, lines[0].words)
        assertEquals(2_000L, lines[0].end)
        assertEquals(null, lines[1].words)
    }


    @Test
    fun timedLrcRegexCompiles() {
        val pattern = java.util.regex.Pattern.compile(
            "[\\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\\]>]"
        )
        org.junit.Assert.assertTrue(pattern.matcher("[00:01.00]x<00:01.00>y").find())
    }

    @Test
    fun attachesSameTimestampTranslationToPreviousPrimary() {
        val raw = """
            [kuwo:21]
            [00:07.94]<0,100>I don't wanna be alone tonight
            [00:10.80]今夜我不想独自一人
            [00:10.80]<0,100>'Lone tonight
            [00:12.33]今夜孤身独
            [00:12.33]<0,100>It's pretty clear that I'm not over you
        """.trimIndent()

        val lines = KuWoLrcxParser.parse(raw)
        assertEquals(3, lines.size)
        assertEquals("I don't wanna be alone tonight", lines[0].text)
        assertEquals("今夜我不想独自一人", lines[0].translation)
        assertEquals("'Lone tonight", lines[1].text)
        assertEquals("今夜孤身独", lines[1].translation)
        assertEquals("It's pretty clear that I'm not over you", lines[2].text)
        assertEquals(null, lines[2].translation)
    }

    @Test
    fun attachesTrailingNonWordTranslationToPreviousCjkPrimary() {
        val raw = """
            [kuwo:21]
            [01:45.19]<0,100>一切从未改,未改......
            [01:52.66]Et ça ne changera jamais, jamais...
        """.trimIndent()

        val lines = KuWoLrcxParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals("一切从未改,未改......", lines[0].text)
        assertEquals("Et ça ne changera jamais, jamais...", lines[0].translation)
    }
}
