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
    }
}
