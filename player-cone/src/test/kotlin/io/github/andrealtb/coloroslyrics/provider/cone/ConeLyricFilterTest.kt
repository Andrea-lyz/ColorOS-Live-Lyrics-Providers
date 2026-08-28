/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConeLyricFilterTest {

    @Test
    fun isTimedLrc_recognizesStandardTimestamps() {
        assertTrue(ConeLyricFilter.isTimedLrc("[00:12.34]Text"))
        assertTrue(ConeLyricFilter.isTimedLrc("[01:23]Text"))
        assertTrue(ConeLyricFilter.isTimedLrc("[00:12:34]Text"))
        assertTrue(ConeLyricFilter.isTimedLrc("<00:12.34>Text"))
        assertFalse(ConeLyricFilter.isTimedLrc("Plain text"))
        assertFalse(ConeLyricFilter.isTimedLrc("[ti:Title]"))
        assertFalse(ConeLyricFilter.isTimedLrc(null))
        assertFalse(ConeLyricFilter.isTimedLrc(""))
    }

    @Test
    fun extractVisibleText_stripsTimestampsAndHeaders() {
        val raw = """
            [ti:Test Title]
            [ar:Test Artist]
            [offset:0]
            [00:01.00]First line
            [00:03.50]<00:03.50>Second <00:04.00>line
        """.trimIndent()
        val visible = ConeLyricFilter.extractVisibleText(raw)
        assertEquals("First line\nSecond line", visible)
    }

    @Test
    fun isNoLyricPlaceholder_detectsPlaceholders() {
        assertTrue(ConeLyricFilter.isNoLyricPlaceholder("[00:00.00]暂无歌词"))
        assertTrue(ConeLyricFilter.isNoLyricPlaceholder("[00:00.00]暂无歌词。"))
        assertTrue(ConeLyricFilter.isNoLyricPlaceholder("[00:00.00]纯音乐，请欣赏"))
        assertTrue(ConeLyricFilter.isNoLyricPlaceholder("[00:00.00]NO LYRICS"))
        assertTrue(ConeLyricFilter.isNoLyricPlaceholder("[00:00.00]instrumental"))
        assertFalse(ConeLyricFilter.isNoLyricPlaceholder("[00:01.00]Real song lyric"))
        assertFalse(ConeLyricFilter.isNoLyricPlaceholder(null))
    }

    @Test
    fun isUsableTimedLyric_filtersInvalidAndPlaceholderLyrics() {
        assertTrue(ConeLyricFilter.isUsableTimedLyric("[00:01.00]Hello world"))
        assertFalse(ConeLyricFilter.isUsableTimedLyric("[00:00.00]暂无歌词"))
        assertFalse(ConeLyricFilter.isUsableTimedLyric("[00:00.00]纯音乐"))
        assertFalse(ConeLyricFilter.isUsableTimedLyric("[ti:Title Only]"))
        assertFalse(ConeLyricFilter.isUsableTimedLyric("No timestamps"))
        assertFalse(ConeLyricFilter.isUsableTimedLyric(null))
        assertFalse(ConeLyricFilter.isUsableTimedLyric(""))
    }
}
