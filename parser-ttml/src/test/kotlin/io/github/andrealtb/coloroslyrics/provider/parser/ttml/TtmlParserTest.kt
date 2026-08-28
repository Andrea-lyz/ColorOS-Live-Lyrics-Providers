/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.ttml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TtmlParserTest {

    @Test
    fun convertsBetterLyricsTtmlToCanonicalEnhancedLrc() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:03.000">
                    <span begin="00:01.000" end="00:01.500">Hello</span> <span begin="00:01.500" end="00:02.000">World</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val result = assertNotNull(TtmlParser.parse(ttml))
        assertEquals("[00:01.000]Hello World", result.plainLrc)
        assertEquals("[00:01.000]<00:01.000>Hello <00:01.500>World<00:02.000>", result.enhancedLrc)
    }

    @Test
    fun mergesAdjacentLatinTtmlSyllableSpansIntoOneTimedWord() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000">
                    <span begin="00:01.000" end="00:01.300">Hel</span><span begin="00:01.300" end="00:01.600">lo</span> <span begin="00:01.600" end="00:02.000">World</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val result = assertNotNull(TtmlParser.parse(ttml))
        assertEquals("[00:01.000]Hello World", result.plainLrc)
        assertEquals("[00:01.000]<00:01.000>Hello <00:01.600>World<00:02.000>", result.enhancedLrc)
    }

    @Test
    fun keepsAdjacentCjkTtmlSpansIndividuallyTimed() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000">
                    <span begin="00:01.000" end="00:01.500">你</span><span begin="00:01.500" end="00:02.000">好</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val result = assertNotNull(TtmlParser.parse(ttml))
        assertEquals("[00:01.000]你好", result.plainLrc)
        assertEquals("[00:01.000]<00:01.000>你<00:01.500>好<00:02.000>", result.enhancedLrc)
    }

    @Test
    fun honorsParagraphEndForUntimedText() {
        val result = assertNotNull(
            TtmlParser.parse(
                """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
                    """<p begin="00:01.000" end="00:02.250">Line</p>""" +
                    """</div></body></tt>"""
            )
        )
        assertEquals(2_250L, result.lines.single().end)
    }

    @Test
    fun prettyPrintedSpansRemainSeparateWords() {
        val result = assertNotNull(
            TtmlParser.parse(
                """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01.000">
                    <span begin="00:01.000" end="00:01.400">Hello</span>
                    <span begin="00:01.400" end="00:02.000">World</span>
                </p></div></body></tt>""".trimIndent()
            )
        )
        assertEquals("[00:01.000]Hello World", result.plainLrc)
    }

    @Test
    fun recognizesMetadataRoleRegardlessOfPrefix() {
        val result = assertNotNull(
            TtmlParser.parse(
                """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata">
                    <body><div><p begin="00:01.000"><span m:role="x-translation">翻译</span>Primary</p></div></body>
                </tt>""".trimIndent()
            )
        )
        assertEquals("[00:01.000]Primary", result.plainLrc)
    }

    @Test
    fun rejectsDoctypeDeclarations() {
        val xml = """<!DOCTYPE tt [<!ENTITY a "boom">]>""" +
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s">&a;</p></div></body></tt>"""
        assertEquals(null, TtmlParser.parse(xml))
    }
}
