/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.proify.lyricon.yrckit.download.response.LyricContent
import io.github.proify.lyricon.yrckit.download.response.LyricResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseConstructedLyricPolicyTest {

    @Test
    fun requestRequiresStableNumericMusicIdAndGeneration() {
        assertNull(
            NeteaseConstructedLyricPolicy.request(
                TrackIdentity(id = "local", title = "Song"),
                1L
            )
        )
        assertNull(
            NeteaseConstructedLyricPolicy.request(
                TrackIdentity(id = "123", title = "Song"),
                0L
            )
        )
        val request = NeteaseConstructedLyricPolicy.request(
            TrackIdentity(id = "123", title = "Song", artist = "Artist"),
            4L
        )
        requireNotNull(request)
        assertEquals(123L, request.musicId)
        assertEquals("123:4", request.key)
    }

    @Test
    fun mapsOriginalAndTranslationWithoutUsingRomaji() {
        val request = requireNotNull(
            NeteaseConstructedLyricPolicy.request(
                TrackIdentity(id = "123", title = "Song", artist = "Artist"),
                4L
            )
        )
        val snapshot = NeteaseConstructedLyricPolicy.snapshot(
            request,
            LyricResponse(
                code = 200,
                lrc = LyricContent(lyric = "[00:01.000]Line"),
                yrc = LyricContent(lyric = "[1000,500](1000,500,0)Line"),
                ytlrc = LyricContent(lyric = "[00:01.000]Translation"),
                romalrc = LyricContent(lyric = "[00:01.000]Romanization")
            )
        )
        val lines = NeteaseLyricDecoder.decode(snapshot)
        assertEquals("123", snapshot.lyricMusicId)
        assertEquals(1, lines.size)
        assertEquals("Line", lines.single().text)
        assertEquals("Translation", lines.single().secondary)
        assertFalse(lines.single().secondary.orEmpty().contains("Romanization"))
    }

    @Test
    fun rejectsLateResultAfterTrackGenerationChanges() {
        val request = requireNotNull(
            NeteaseConstructedLyricPolicy.request(
                TrackIdentity(id = "123", title = "Song"),
                4L
            )
        )
        assertTrue(
            NeteaseConstructedLyricPolicy.isCurrent(
                request,
                TrackIdentity(id = "123", title = "Song"),
                4L
            )
        )
        assertFalse(
            NeteaseConstructedLyricPolicy.isCurrent(
                request,
                TrackIdentity(id = "456", title = "Next"),
                5L
            )
        )
    }
}
