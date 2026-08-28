/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConeBroadcastLyricExtractorTest {

    @Test
    fun extract_withValidActionAndTimedLrc_returnsLyric() {
        val raw = "[00:01.00]Hello world\n[00:05.00]Second line"
        val result = ConeBroadcastLyricExtractor.extract(
            ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED,
            raw
        )
        assertEquals(raw, result)
    }

    @Test
    fun extract_withWrongAction_returnsNull() {
        val raw = "[00:01.00]Hello world"
        val result = ConeBroadcastLyricExtractor.extract(
            "wrong.action",
            raw
        )
        assertNull(result)
    }

    @Test
    fun extract_withNullOrBlank_returnsNull() {
        assertNull(ConeBroadcastLyricExtractor.extract(ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED, null))
        assertNull(ConeBroadcastLyricExtractor.extract(ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED, ""))
        assertNull(ConeBroadcastLyricExtractor.extract(ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED, "   "))
    }

    @Test
    fun extract_withUntimedText_returnsNull() {
        val result = ConeBroadcastLyricExtractor.extract(
            ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED,
            "Just plain text without time tags"
        )
        assertNull(result)
    }

    @Test
    fun extract_withNoLyricPlaceholder_returnsNull() {
        val placeholders = listOf(
            "[00:00.00]暂无歌词",
            "[00:00.00]暂无歌词。",
            "[00:00.00]无歌词",
            "[00:00.00]纯音乐",
            "[00:00.00]纯音乐，请欣赏",
            "[00:00.00]No Lyric",
            "[00:00.00]Instrumental"
        )
        for (ph in placeholders) {
            val result = ConeBroadcastLyricExtractor.extract(
                ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED,
                ph
            )
            assertNull(result, "Placeholder should be filtered: $ph")
        }
    }

    @Test
    fun extract_withMetadataHeadersAndValidLines_returnsTrimmedLyric() {
        val raw = """
            [ti:Song Title]
            [ar:Artist Name]
            [al:Album Name]
            [00:01.50]Actual lyric line
            [00:04.20]Next line
        """.trimIndent()
        val result = ConeBroadcastLyricExtractor.extract(
            ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED,
            raw
        )
        assertEquals(raw, result)
    }
}
