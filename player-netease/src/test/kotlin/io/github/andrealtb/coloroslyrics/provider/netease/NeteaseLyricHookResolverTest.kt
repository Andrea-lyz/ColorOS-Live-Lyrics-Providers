/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricHookResolverTest {

    @Test
    fun lyricWriterRequiresLyricInfoMusicInfoVoidAndAnchor() {
        assertTrue(
            NeteaseLyricHookResolver.matchesLyricWriteMethod(
                listOf(
                    NeteasePlayerConstants.LYRIC_INFO_CLASS,
                    NeteasePlayerConstants.MUSIC_INFO_CLASS
                ),
                Void.TYPE.name,
                listOf("lyricInfo")
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesLyricWriteMethod(
                listOf(
                    NeteasePlayerConstants.LYRIC_INFO_CLASS,
                    NeteasePlayerConstants.MUSIC_INFO_CLASS
                ),
                Void.TYPE.name,
                emptyList()
            )
        )
    }

    @Test
    fun officialEncoderRequiresThreeStringsAndJsonAnchors() {
        assertTrue(
            NeteaseLyricHookResolver.matchesOfficialEncoder(
                List(3) { String::class.java.name },
                String::class.java.name,
                listOf("lyric", "songName", "artist")
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesOfficialEncoder(
                List(2) { String::class.java.name },
                String::class.java.name,
                listOf("lyric", "songName", "artist")
            )
        )
    }

    @Test
    fun dispatchRequiresExactHandlerWhatAndLyricPayload() {
        assertTrue(
            NeteaseLyricHookResolver.matchesLyricDispatch(
                "jp0.t",
                "jp0.t",
                16,
                NeteasePlayerConstants.LYRIC_INFO_CLASS
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesLyricDispatch(
                "jp0.t",
                "ce0.p",
                16,
                NeteasePlayerConstants.LYRIC_INFO_CLASS
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesLyricDispatch(
                "jp0.t",
                "jp0.t",
                3,
                NeteasePlayerConstants.LYRIC_INFO_CLASS
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesLyricDispatch(
                "jp0.t",
                "jp0.t",
                16,
                NeteasePlayerConstants.MUSIC_INFO_CLASS
            )
        )
    }

    @Test
    fun currentMusicAccessorIsUniqueShapeNotName() {
        assertTrue(
            NeteaseLyricHookResolver.matchesCurrentMusicAccessor(
                emptyList(),
                NeteasePlayerConstants.MUSIC_INFO_CLASS
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesCurrentMusicAccessor(
                listOf("int"),
                NeteasePlayerConstants.MUSIC_INFO_CLASS
            )
        )
    }

    @Test
    fun trackBindUsesMusicInfoVoidShapeAndRejectsHandleMessage() {
        assertTrue(
            NeteaseLyricHookResolver.matchesTrackBindMethod(
                listOf(NeteasePlayerConstants.MUSIC_INFO_CLASS),
                Void.TYPE.name,
                "R"
            )
        )
        assertTrue(
            NeteaseLyricHookResolver.matchesTrackBindMethod(
                listOf(NeteasePlayerConstants.MUSIC_INFO_CLASS),
                Void.TYPE.name,
                "g0"
            )
        )
        assertFalse(
            NeteaseLyricHookResolver.matchesTrackBindMethod(
                listOf(NeteasePlayerConstants.MUSIC_INFO_CLASS),
                Void.TYPE.name,
                "handleMessage"
            )
        )
    }
}
