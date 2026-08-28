/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouLyricDataDecoderTest {

    @Test
    fun readsStandardGettersAndKeepsTranslationSeparateFromRomaji() {
        val data = StandardLyricData(
            words = arrayOf(arrayOf("Hello", " world")),
            rowBeginTime = longArrayOf(1_000L),
            rowDelayTime = longArrayOf(1_000L),
            wordBeginTime = arrayOf(longArrayOf(1_000L, 1_400L)),
            wordDelayTime = arrayOf(longArrayOf(400L, 600L)),
            translateWords = arrayOf(arrayOf("你好", " 世界")),
            transliterationWords = arrayOf(arrayOf("ha", "lo"))
        )

        val lines = KuGouLyricDataDecoder.decode(data)
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals("你好 世界", lines[0].secondary)
        assertEquals(2, lines[0].words.orEmpty().size)
        assertEquals(1_000L, lines[0].words!![0].begin)
        assertEquals("Hello", lines[0].words!![0].text)
        assertTrue(lines[0].secondary?.contains("ha") != true)
    }

    @Test
    fun readsLiteObfuscatedAccessors() {
        val data = LiteLyricData(
            f = arrayOf(arrayOf("逐", "字")),
            d = longArrayOf(12_340L),
            e = longArrayOf(3_200L),
            i = arrayOf(longArrayOf(0L, 500L)),
            j = arrayOf(longArrayOf(500L, 600L)),
            k = arrayOf(arrayOf("word", " by word"))
        )

        val lines = KuGouLyricDataDecoder.decode(data)
        assertEquals("逐字", lines.single().text)
        assertEquals("word by word", lines.single().secondary)
        assertEquals(2, lines.single().words.orEmpty().size)
        assertEquals(12_340L, lines.single().words!![0].begin)
        assertEquals(12_840L, lines.single().words!![1].begin)
    }

    @Test
    fun unwrapsLyricDataFieldFromLoadResult() {
        val inner = StandardLyricData(
            words = arrayOf(arrayOf("only")),
            rowBeginTime = longArrayOf(0L),
            rowDelayTime = longArrayOf(500L)
        )
        val wrapper = LoadResult(inner)
        val extracted = KuGouLyricDataDecoder.lyricDataFromResult(wrapper)
        assertEquals(inner, extracted)
    }

    @Test
    fun loadResultStringFieldsDoNotCrashDecoder() {
        val wrapper = CrashyLoadResult(
            f = "not a matrix",
            d = "/sdcard/kugou/lyric.krc"
        )
        assertEquals(null, KuGouLyricDataDecoder.lyricDataFromResult(wrapper))
        assertTrue(KuGouLyricDataDecoder.decode(wrapper).isEmpty())
    }

    @Test
    fun prefersDecodableLyricDataOverStringFieldsOnLoadResult() {
        val inner = StandardLyricData(
            words = arrayOf(arrayOf("only")),
            rowBeginTime = longArrayOf(0L),
            rowDelayTime = longArrayOf(500L)
        )
        val wrapper = MixedLoadResult(f = "noise", lyricData = inner)
        assertEquals(inner, KuGouLyricDataDecoder.lyricDataFromResult(wrapper))
        assertEquals("only", KuGouLyricDataDecoder.decode(inner).single().text)
    }

    class LoadResult(@JvmField val lyricData: StandardLyricData)

    class CrashyLoadResult(
        @JvmField val f: String,
        @JvmField val d: String
    )

    class MixedLoadResult(
        @JvmField val f: String,
        @JvmField val lyricData: StandardLyricData
    )

    class StandardLyricData(
        private val words: Array<Array<String>>,
        private val rowBeginTime: LongArray,
        private val rowDelayTime: LongArray,
        private val wordBeginTime: Array<LongArray>? = null,
        private val wordDelayTime: Array<LongArray>? = null,
        private val translateWords: Array<Array<String>>? = null,
        private val transliterationWords: Array<Array<String>>? = null
    ) {
        fun getWords() = words
        fun getRowBeginTime() = rowBeginTime
        fun getRowDelayTime() = rowDelayTime
        fun getWordBeginTime() = wordBeginTime
        fun getWordDelayTime() = wordDelayTime
        fun getTranslateWords() = translateWords
        fun getTransliterationWords() = transliterationWords
    }

    class LiteLyricData(
        @JvmField val f: Array<Array<String>>,
        @JvmField val d: LongArray,
        @JvmField val e: LongArray,
        @JvmField val i: Array<LongArray>? = null,
        @JvmField val j: Array<LongArray>? = null,
        @JvmField val k: Array<Array<String>>? = null
    ) {
        fun z() = f
        fun o() = d
        fun p() = e
        fun v() = i
        fun w() = j
        fun t() = k
    }
}
