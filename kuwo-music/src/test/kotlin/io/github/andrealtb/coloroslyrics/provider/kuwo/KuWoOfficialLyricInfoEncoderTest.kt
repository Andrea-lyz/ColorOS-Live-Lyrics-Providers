/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuWoOfficialLyricInfoEncoderTest {

    @Test
    fun emitsOfficialTimedLyricInfoWithoutExternalBridgeFields() {
        val song = Song(
            id = "rid-1",
            name = "Song",
            artist = "Artist",
            duration = 120_000L,
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 2_000L,
                    duration = 1_000L,
                    text = "hello"
                )
            )
        )

        val encoded = KuWoOfficialLyricInfoEncoder.encode(song, 7L)
        requireNotNull(encoded)
        assertTrue(encoded.value.contains("\"songName\":\"Song\""))
        assertTrue(encoded.value.contains("\"artist\":\"Artist\""))
        assertTrue(encoded.value.contains("\"songId\":\"rid-1\""))
        assertTrue(encoded.value.contains("\"id\":\"\""))
        assertTrue(encoded.value.contains("\"lyricType\":0"))
        assertTrue(encoded.value.contains("\"noLyric\":false"))
        assertTrue(encoded.value.contains("\"provider\":\"cn.kuwo.player\""))
        assertTrue(!encoded.value.contains("\"provider\":\"lockscreen-lyrics-module\""))
        assertTrue(encoded.value.contains("\"source\":\"kuwo-internal\""))
        assertTrue(encoded.value.contains("[00:01.000]"))
        assertTrue(encoded.value.contains("\"sessionGeneration\":7"))
        assertEquals("Song", encoded.plainLyric.substringAfter("[ti:").substringBefore("]"))
    }
}
