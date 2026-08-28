/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QishuiInternalLyricsDecoderTest {
    @Test
    fun runtimeGetAContextAndInternalTrackLyricAreDecoded() {
        val playable = FakePlayable()
        assertSame(
            playable,
            QishuiInternalLyricsDecoder.resolvePlayable(FakeRemoteControlContext(playable))
        )
        val publication = QishuiInternalLyricsDecoder.decode(
            playable,
            TrackIdentity(id = "7123", title = "Host title", artist = "Host artist")
        )!!
        assertEquals("Host title", publication.track.title)
        assertEquals("Host artist", publication.track.artist)
        assertEquals("完整歌词", publication.lines.single().text)
        assertEquals("Full lyric", publication.lines.single().secondary)
    }

    private class FakeRemoteControlContext(private val playable: FakePlayable) {
        fun getA(): FakePlayable = playable
    }

    private class FakePlayable {
        fun getPlayableId(): String = "7123"
        fun getName(): String = "Internal title"
        fun getArtistName(): String = "Internal artist"
        fun getDuration(): Long = 180_000L
        fun getLyric(): FakeLyric = FakeLyric()
    }

    private class FakeLyric {
        fun getType(): String = "lrc"
        fun getContent(): String = "[00:01.000]完整歌词"
        fun getLangTranslations(): Map<String, FakeTranslation> =
            mapOf("EN-US" to FakeTranslation())
    }

    private class FakeTranslation {
        fun getType(): String = "lrc"
        fun getContent(): String = "[00:01.000]Full lyric"
    }
}
