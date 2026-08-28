/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import org.junit.Assert.assertEquals
import org.junit.Test

class QishuiKtvLyricParserTest {
    @Test
    fun prefixWordTagsBecomeAbsoluteTimes() {
        val lines = QishuiKtvLyricParser.parse(
            "[12000,1500]<0,400,0>汽<400,500,0>水<900,600,0>音乐"
        )
        assertEquals(1, lines.size)
        assertEquals("汽水音乐", lines.single().text)
        assertEquals(12_000L, lines.single().words!![0].begin)
        assertEquals(12_400L, lines.single().words!![1].begin)
        assertEquals(13_500L, lines.single().end)
    }

    @Test
    fun suffixCompatibilityFormKeepsTrailingText() {
        val lines = QishuiKtvLyricParser.parse(
            "[5000,1000]汽<0,500,0>水音乐<500,500,0>"
        )
        assertEquals("汽水音乐", lines.single().text)
        assertEquals(listOf("汽", "水音乐"), lines.single().words!!.map { it.text })
    }
}
