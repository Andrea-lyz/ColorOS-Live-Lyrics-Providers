/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouKrcFileDecoderTest {

    @Test
    fun mapsDecryptedKrcWordsAndTypeOneTranslation() {
        val language = java.util.Base64.getEncoder().encodeToString(
            """{"content":[{"language":0,"lyricContent":[["First line"]],"type":1}],"version":1}"""
                .toByteArray()
        )
        val decrypted = """
            [id:1]
            [language:$language]
            [12340,3200]<0,500,0>逐<500,600,0>字
        """.trimIndent()

        val lines = KuGouKrcFileDecoder.decodeDecryptedKrc(decrypted)
        assertEquals(1, lines.size)
        assertEquals("逐字", lines[0].text)
        assertEquals(2, lines[0].words.orEmpty().size)
        assertEquals(12_340L, lines[0].words!![0].begin)
        assertEquals("逐", lines[0].words!![0].text)
        assertEquals("First line", lines[0].secondary)
    }

    @Test
    fun parsesRelativeWordTags() {
        val words = KuGouKrcFileDecoder.parseKrcWords(
            "[12340,3200]<0,500,0>Hello<500,600,0> world",
            12_340L
        )
        assertEquals(2, words.size)
        assertEquals(12_340L, words[0].begin)
        assertEquals(12_840L, words[1].begin)
        assertEquals("Hello", words[0].text)
        assertEquals(" world", words[1].text)
        assertTrue(words.all { it is LyricWord })
    }
}
