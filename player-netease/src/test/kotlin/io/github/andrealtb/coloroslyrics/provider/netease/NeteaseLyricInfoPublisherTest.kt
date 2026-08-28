/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NeteaseLyricInfoPublisherTest {

    @Before
    fun resetPublisher() {
        NeteaseLyricInfoPublisher.resetForTests()
    }

    @Test
    fun onTrackChangedKeepsPublicationForSameTrack() {
        val publication = samplePublication("314159", "夜曲")
        NeteaseLyricInfoPublisher.onLyricReady(publication)
        assertTrue(
            NeteaseLyricInfoPublisher.onTrackChanged(
                2L,
                TrackIdentity(id = "314159", title = "夜曲", artist = "周杰伦")
            )
        )
    }

    @Test
    fun onTrackChangedClearsPublicationForDifferentTrack() {
        NeteaseLyricInfoPublisher.onLyricReady(samplePublication("314159", "夜曲"))
        assertFalse(
            NeteaseLyricInfoPublisher.onTrackChanged(
                2L,
                TrackIdentity(id = "2", title = "七里香", artist = "周杰伦")
            )
        )
    }

    @Test
    fun publicationCarriesExplicitPayloadMode() {
        assertEquals(
            NeteasePayloadMode.OFFICIAL_APPEND,
            samplePublication("314159", "夜曲").payloadMode
        )
        assertEquals(
            NeteasePayloadMode.CONSTRUCTED,
            samplePublication("314159", "夜曲").copy(
                payloadMode = NeteasePayloadMode.CONSTRUCTED
            ).payloadMode
        )
    }

    private fun samplePublication(id: String, title: String): NeteasePublication =
        NeteasePublication(
            track = TrackIdentity(id = id, title = title, artist = "周杰伦"),
            lines = listOf(
                RichLyricLine(
                    begin = 1000L,
                    end = 2000L,
                    duration = 1000L,
                    text = title
                )
            ),
            generation = 1L,
            captureOrigin = "test",
            payloadMode = NeteasePayloadMode.OFFICIAL_APPEND
        )

}
