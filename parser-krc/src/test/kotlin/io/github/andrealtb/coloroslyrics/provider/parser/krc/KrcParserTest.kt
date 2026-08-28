/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc

import kotlin.test.Test
import kotlin.test.assertEquals

class KrcParserTest {

    @Test
    fun testParseSimpleKrc() {
        val raw = """
            [ti:Test Title]
            [ar:Test Artist]
            [1000,2000]<0,1000,0>Hello <1000,1000,0>World
            [3000,2000]<0,1000,0>Line <1000,1000,0>Two
        """.trimIndent()

        val doc = KrcParser.parse(raw)
        assertEquals("Test Title", doc.metadata["ti"])
        assertEquals("Test Artist", doc.metadata["ar"])
        assertEquals(2, doc.lines.size)
        assertEquals(1000L, doc.lines[0].begin)
        assertEquals(3000L, doc.lines[0].end)
        assertEquals("Hello World", doc.lines[0].text)
        assertEquals(2, doc.lines[0].words.orEmpty().size)
        assertEquals(1000L, doc.lines[0].words.orEmpty()[0].begin)
        assertEquals(2000L, doc.lines[0].words.orEmpty()[1].begin)
    }

    @Test
    fun wordTimingsStayAttachedWhenAnEarlierTimedLineHasNoWordTags() {
        val doc = KrcParser.parse(
            "[1000,500]Plain line\n[2000,1000]<0,400,0>Timed<400,600,0> line"
        )

        assertEquals(2, doc.lines.size)
        assertEquals(null, doc.lines[0].words)
        assertEquals(listOf(2000L, 2400L), doc.lines[1].words.orEmpty().map { it.begin })
    }
}
