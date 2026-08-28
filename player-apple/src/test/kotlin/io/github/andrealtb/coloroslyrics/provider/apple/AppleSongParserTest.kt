/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleSongParserTest {
    @Test
    fun walksSectionsLinesAndWordsFromFakeJniTree() {
        val native = FakeSongNative(
            adamIdValue = 1440935467L,
            durationValue = 178000,
            sectionValues = listOf(
                FakeSectionNative(
                    lineValues = listOf(
                        FakeLineNative(
                            beginValue = 1000,
                            endValue = 2500,
                            durationValue = 1500,
                            lineText = "It's a cruel summer",
                            translationText = "这是一个残酷的夏天",
                            pronunciationText = "Its a cruel summer",
                            wordValues = listOf(
                                FakeWordNative(1000, 1600, 600, "It's"),
                                FakeWordNative(1600, 2500, 900, " a cruel summer")
                            )
                        )
                    )
                )
            )
        )

        val parsed = AppleSongParser.parse(native)!!
        assertEquals("1440935467", parsed.adamId)
        assertEquals(178000, parsed.durationMs)
        assertEquals(1, parsed.lyrics.size)
        assertEquals("It's a cruel summer", parsed.lyrics[0].htmlLineText)
        assertEquals("这是一个残酷的夏天", parsed.lyrics[0].htmlTranslationLineText)
        assertEquals(2, parsed.lyrics[0].words.size)
        assertEquals(1000, parsed.lyrics[0].words[0].begin)
    }

    @Test
    fun rejectsMissingAdamId() {
        assertNull(AppleSongParser.parse(FakeSongNative(adamIdValue = 0L, durationValue = 1)))
    }

    @Test
    fun appliesTranslationLanguageWhenPresent() {
        val native = FakeSongNative(adamIdValue = 1L, durationValue = 1)
        assertEquals(true, AppleSongParser.applySystemTranslation(native, "zh-Hans"))
        assertEquals("zh-Hans", native.translation)
        assertEquals(false, AppleSongParser.applySystemTranslation(native, " "))
    }

    private class FakeSongNative(
        private val adamIdValue: Long,
        private val durationValue: Int,
        private val sectionValues: List<FakeSectionNative> = emptyList()
    ) {
        var translation: String? = null

        fun getAdamId(): Long = adamIdValue
        fun getDuration(): Int = durationValue
        fun getSections(): FakeVector<FakePtr<FakeSectionNative>> =
            FakeVector(sectionValues.map(::FakePtr))
        fun setTranslation(language: String): Boolean {
            translation = language
            return true
        }
    }

    private class FakeSectionNative(private val lineValues: List<FakeLineNative>) {
        fun getLines(): FakeVector<FakePtr<FakeLineNative>> = FakeVector(lineValues.map(::FakePtr))
    }

    private class FakeLineNative(
        private val beginValue: Int,
        private val endValue: Int,
        private val durationValue: Int,
        private val lineText: String,
        private val translationText: String,
        private val pronunciationText: String,
        private val wordValues: List<FakeWordNative>
    ) {
        fun getBegin(): Int = beginValue
        fun getEnd(): Int = endValue
        fun getDuration(): Int = durationValue
        fun getHtmlLineText(): String = lineText
        fun getHtmlTranslationLineText(): String = translationText
        fun getHtmlPronunciationLineText(): String = pronunciationText
        fun getHtmlBackgroundVocalsLineText(): String? = null
        fun getWords(): FakeVector<FakePtr<FakeWordNative>> = FakeVector(wordValues.map(::FakePtr))
        fun getBackgroundWords(): FakeVector<FakePtr<FakeWordNative>> = FakeVector(emptyList())
    }

    private class FakeWordNative(
        private val beginValue: Int,
        private val endValue: Int,
        private val durationValue: Int,
        private val lineText: String
    ) {
        fun getBegin(): Int = beginValue
        fun getEnd(): Int = endValue
        fun getDuration(): Int = durationValue
        fun getHtmlLineText(): String = lineText
        fun isWhitespace(): Boolean = false
    }

    private class FakePtr<T>(private val value: T) {
        fun get(): T = value
    }

    private class FakeVector<T>(private val items: List<T>) {
        fun size(): Long = items.size.toLong()
        fun get(index: Long): T = items[index.toInt()]
    }
}
