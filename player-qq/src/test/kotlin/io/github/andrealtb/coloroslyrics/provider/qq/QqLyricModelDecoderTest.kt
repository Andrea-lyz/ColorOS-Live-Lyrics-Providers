/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqLyricModelDecoderTest {

    @Test
    fun decodesWordTimedLinesAndIgnoresRomaAsTranslation() {
        val primary = FakeDocument(
            CopyOnWriteArrayList(
                listOf(
                    FakeLine(
                        a = "Hello world",
                        b = 1_000L,
                        c = 2_000L,
                        g = arrayListOf(
                            FakeWord(1_000L, 400L, 0, 5, "Hello"),
                            FakeWord(1_400L, 600L, 6, 11, "world")
                        )
                    )
                )
            )
        )
        val trans = FakeDocument(
            CopyOnWriteArrayList(
                listOf(FakeLine("你好世界", 1_000L, 2_000L, arrayListOf()))
            )
        )
        val merged = QqLyricModelDecoder.mergeTranslation(
            QqLyricModelDecoder.decodePrimary(primary),
            QqLyricModelDecoder.decodeTranslation(trans)
        )
        assertEquals(1, merged.size)
        assertEquals("Hello world", merged[0].text)
        assertEquals("你好世界", merged[0].secondary)
        assertEquals(1_000L, merged[0].words!![0].begin)
        assertEquals("Hello", merged[0].words!![0].text)
    }

    @Test
    fun alignsTranslationMonotonicallyAndSkipsSlashPlaceholders() {
        val primary = listOf(
            QqLyricModelDecoder.decodePrimary(
                FakeDocument(
                    CopyOnWriteArrayList(
                        listOf(
                            FakeLine("one", 0L, 1_000L, arrayListOf()),
                            FakeLine("two", 2_000L, 1_000L, arrayListOf())
                        )
                    )
                )
            )
        ).flatten()
        val trans = QqLyricModelDecoder.decodeTranslation(
            FakeDocument(
                CopyOnWriteArrayList(
                    listOf(
                        FakeLine("//", 0L, 1_000L, arrayListOf()),
                        FakeLine("二", 2_000L, 1_000L, arrayListOf())
                    )
                )
            )
        )
        val merged = QqLyricModelDecoder.mergeTranslation(primary, trans)
        assertNull(merged[0].secondary)
        assertEquals("二", merged[1].secondary)
    }

    @Test
    fun attachesTranslationWhenQrcLineStartsTwoSecondsBeforeFirstWord() {
        // Love Story line 7 from lyrics-log-20260827-073906.txt:
        // primary begin=00:30.362, first word=00:32.362, translation at singing start.
        val primary = QqLyricModelDecoder.decodePrimary(
            FakeDocument(
                CopyOnWriteArrayList(
                    listOf(
                        FakeLine(
                            a = "I'm standing there",
                            b = 23_096L,
                            c = 9_266L,
                            g = arrayListOf(FakeWord(23_525L, 400L, 0, 2, "I'm"))
                        ),
                        FakeLine(
                            a = "See the lights see the party the ball gowns",
                            b = 30_362L,
                            c = 5_734L,
                            g = arrayListOf(
                                FakeWord(32_362L, 400L, 0, 3, "See")
                            )
                        ),
                        FakeLine(
                            a = "See you make your way through the crowd",
                            b = 36_096L,
                            c = 5_423L,
                            g = arrayListOf(FakeWord(36_380L, 400L, 0, 3, "See"))
                        )
                    )
                )
            )
        )
        val translation = QqLyricModelDecoder.decodeTranslation(
            FakeDocument(
                CopyOnWriteArrayList(
                    listOf(
                        FakeLine("我站在那里", 23_096L, 9_266L, arrayListOf()),
                        FakeLine("看见灯光看见派对看见礼服", 32_362L, 3_734L, arrayListOf()),
                        FakeLine("看见你穿过人群走来", 36_096L, 5_423L, arrayListOf())
                    )
                )
            )
        )
        val merged = QqLyricModelDecoder.mergeTranslation(primary, translation)
        assertEquals("我站在那里", merged[0].secondary)
        assertEquals("看见灯光看见派对看见礼服", merged[1].secondary)
        assertEquals("看见你穿过人群走来", merged[2].secondary)
    }

    @Test
    fun attachesTranslationWhenFirstWordIsSixHundredMillisLater() {
        val primary = listOf(
            timedLine(
                text = "And my daddy said",
                begin = 51_408L,
                firstWord = 52_008L
            )
        )
        val translation = listOf(
            timedLine(text = "而我爸爸说", begin = 52_008L)
        )
        val merged = QqLyricModelDecoder.mergeTranslation(primary, translation)
        assertEquals("而我爸爸说", merged[0].secondary)
    }

    @Test
    fun consumesSlashPlaceholderBeforeACloseFollowingLine() {
        val primary = listOf(
            timedLine(text = "one", begin = 1_000L),
            timedLine(text = "two", begin = 1_300L)
        )
        val translation = listOf(
            timedLine(text = "//", begin = 1_000L),
            timedLine(text = "二", begin = 1_300L)
        )
        val merged = QqLyricModelDecoder.mergeTranslation(primary, translation)
        assertNull(merged[0].secondary)
        assertEquals("二", merged[1].secondary)
    }

    @Test
    fun matchesTranslationDriftedByTwoHundredTwentyMillis() {
        val primary = listOf(
            timedLine(text = "one", begin = 1_000L),
            timedLine(text = "two", begin = 5_000L)
        )
        val translation = listOf(
            timedLine(text = "一", begin = 1_220L),
            timedLine(text = "二", begin = 5_220L)
        )
        val merged = QqLyricModelDecoder.mergeTranslation(primary, translation)
        assertEquals("一", merged[0].secondary)
        assertEquals("二", merged[1].secondary)
    }

    @Test
    fun doesNotAttachOneTranslationToTwoClosePrimaryLines() {
        val primary = listOf(
            timedLine(text = "one", begin = 1_000L),
            timedLine(text = "two", begin = 1_060L)
        )
        val translation = listOf(timedLine(text = "一", begin = 1_000L))
        val merged = QqLyricModelDecoder.mergeTranslation(primary, translation)
        assertEquals("一", merged[0].secondary)
        assertNull(merged[1].secondary)
    }

    @Test
    fun doesNotAttachTranslationFiveSecondsAway() {
        val primary = listOf(timedLine(text = "one", begin = 1_000L))
        val translation = listOf(timedLine(text = "一", begin = 6_000L))
        val merged = QqLyricModelDecoder.mergeTranslation(primary, translation)
        assertNull(merged[0].secondary)
    }

    @Test
    fun decodeTranslationFallsBackToWordTextWhenLineTextIsBlank() {
        val translation = QqLyricModelDecoder.decodeTranslation(
            FakeDocument(
                CopyOnWriteArrayList(
                    listOf(
                        FakeLine(
                            a = "",
                            b = 1_000L,
                            c = 2_000L,
                            g = arrayListOf(FakeWord(1_000L, 400L, 0, 2, "看见灯光"))
                        )
                    )
                )
            )
        )
        assertEquals(1, translation.size)
        assertEquals("看见灯光", translation[0].text)
        assertNull(translation[0].words)
    }

    @Test
    fun convertsRelativeWordOffsetsOnTheWholeLine() {
        val document = FakeDocument(
            CopyOnWriteArrayList(
                listOf(
                    FakeLine(
                        a = "abc",
                        b = 6_835L,
                        c = 2_093L,
                        g = arrayListOf(
                            FakeWord(0L, 200L, 0, 1, "a"),
                            FakeWord(779L, 200L, 1, 2, "b")
                        )
                    )
                )
            )
        )
        val lines = QqLyricModelDecoder.decodePrimary(document)
        assertEquals(6_835L, lines[0].words!![0].begin)
        assertEquals(7_614L, lines[0].words!![1].begin)
    }

    private fun timedLine(
        text: String,
        begin: Long,
        firstWord: Long? = null
    ): RichLyricLine {
        val words = firstWord?.let {
            listOf(
                LyricWord(
                    begin = it,
                    end = it + 400L,
                    duration = 400L,
                    text = text.take(4).ifBlank { "x" }
                )
            )
        }
        return RichLyricLine(
            begin = begin,
            end = begin + 3_000L,
            duration = 3_000L,
            text = text,
            words = words
        )
    }

    class FakeDocument(@JvmField val e: CopyOnWriteArrayList<FakeLine>)

    class FakeLine(
        @JvmField val a: String,
        @JvmField val b: Long,
        @JvmField val c: Long,
        @JvmField val g: ArrayList<FakeWord>
    )

    class FakeWord(
        @JvmField val a: Long,
        @JvmField val b: Long,
        @JvmField val c: Int,
        @JvmField val d: Int,
        @JvmField val e: String
    )
}
