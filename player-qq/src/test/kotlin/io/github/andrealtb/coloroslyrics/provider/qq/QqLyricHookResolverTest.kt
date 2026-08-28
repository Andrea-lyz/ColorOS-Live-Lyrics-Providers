/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqLyricHookResolverTest {

    @Test
    fun acceptsDocumentedSeedlingMethodShape() {
        assertTrue(
            QqLyricHookResolver.matchesSeedlingMethod(
                listOf(
                    "android.support.v4.media.MediaMetadataCompat\$Builder",
                    QqPlayerConstants.SONG_INFO,
                    QqPlayerConstants.LYRIC_ENGINE_DOCUMENT
                ),
                listOf("lyricInfo", "transLyric", "songName")
            )
        )
    }

    @Test
    fun rejectsWrongSeedlingSignature() {
        assertFalse(
            QqLyricHookResolver.matchesSeedlingMethod(
                listOf(
                    "android.support.v4.media.MediaMetadataCompat\$Builder",
                    QqPlayerConstants.SONG_INFO
                ),
                listOf("lyricInfo", "transLyric")
            )
        )
        assertFalse(
            QqLyricHookResolver.matchesSeedlingMethod(
                listOf(
                    "android.media.MediaMetadata\$Builder",
                    QqPlayerConstants.SONG_INFO,
                    QqPlayerConstants.LYRIC_ENGINE_DOCUMENT
                ),
                listOf("lyricInfo", "transLyric")
            )
        )
        assertFalse(
            QqLyricHookResolver.matchesSeedlingMethod(
                listOf(
                    "android.support.v4.media.MediaMetadataCompat\$Builder",
                    QqPlayerConstants.SONG_INFO,
                    QqPlayerConstants.LYRIC_ENGINE_DOCUMENT
                ),
                listOf("lyricInfo")
            )
        )
    }

    @Test
    fun acceptsDocumentedOnLoadSuc() {
        assertTrue(
            QqLyricHookResolver.matchesOnLoadSuc(
                QqPlayerConstants.REMOTE_LYRIC_CONTROLLER,
                "onLoadSuc",
                listOf(QqPlayerConstants.LYRIC_LOAD_BEAN)
            )
        )
        assertFalse(
            QqLyricHookResolver.matchesOnLoadSuc(
                QqPlayerConstants.REMOTE_LYRIC_CONTROLLER,
                "onLoadSuc",
                listOf(QqPlayerConstants.SONG_INFO)
            )
        )
    }
}
