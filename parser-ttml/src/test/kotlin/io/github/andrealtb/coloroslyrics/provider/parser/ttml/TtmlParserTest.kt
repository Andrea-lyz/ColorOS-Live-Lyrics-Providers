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
}
