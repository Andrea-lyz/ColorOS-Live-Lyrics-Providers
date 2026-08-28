/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricDecoderTest {

    @Test
    fun prefersYrcWordsAndMergesLrcTranslation() {
        val snapshot = NeteaseLyricInfoReader.Snapshot(
            track = io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity(
                id = "1",
                title = "Song",
                artist = "Artist"
            ),
            lyricMusicId = "1",
            lrc = "[00:01.00]Hello world",
            yrc = "[1000,1400](1000,400,0)Hello(1400,600,0) world",
            lrcTranslate = "[00:01.00]你好世界",
            yrcTranslate = null
        )
        val lines = NeteaseLyricDecoder.decode(snapshot)
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals("你好世界", lines[0].secondary)
        assertEquals(1_000L, lines[0].words!![0].begin)
        assertEquals("Hello", lines[0].words!![0].text)
    }

    @Test
    fun neverTreatsRomeAsTranslationAndSkipsSlashPlaceholders() {
        val primary = NeteaseLyricDecoder.parsePrimary(
            yrc = "[0,1000](0,400,0)one\n[2000,1000](2000,400,0)two",
            lrc = null
        )
        val translation = NeteaseLyricDecoder.parseTranslation(
            yrcTranslate = "[00:00.00]//\n[00:02.00]二",
            lrcTranslate = null
        )
        val merged = NeteaseLyricDecoder.mergeTranslation(primary, translation)
        assertNull(merged[0].secondary)
        assertEquals("二", merged[1].secondary)
        assertFalse(NeteaseLyricDecoder.looksLikeYrc("[00:01.00]hello"))
        assertTrue(NeteaseLyricDecoder.looksLikeYrc("[1000,500](1000,200,0)字"))
    }

    @Test
    fun prefersYrcTranslateOverLrcTranslate() {
        val translation = NeteaseLyricDecoder.parseTranslation(
            yrcTranslate = "[1000,400](1000,400,0)译",
            lrcTranslate = "[00:01.00]其他"
        )
        assertEquals("译", translation.single().text)
    }

    @Test
    fun attachesTranslationWhenYrcLineStartsBeforeFirstWord() {
        val primary = NeteaseLyricDecoder.parsePrimary(
            yrc = "[30362,5734](32362,400,0)See(32762,800,0) the lights",
            lrc = null
        )
        val translation = NeteaseLyricDecoder.parseTranslation(
            yrcTranslate = null,
            lrcTranslate = "[00:32.362]看见灯光"
        )
        val merged = NeteaseLyricDecoder.mergeTranslation(primary, translation)
        assertEquals("看见灯光", merged.single().secondary)
    }
}
