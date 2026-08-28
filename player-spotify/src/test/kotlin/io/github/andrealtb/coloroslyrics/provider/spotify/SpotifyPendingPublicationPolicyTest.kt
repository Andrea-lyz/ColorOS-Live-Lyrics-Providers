/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpotifyPendingPublicationPolicyTest {
    private val track = TrackIdentity(
        id = "spotify:track:1",
        title = "Title",
        artist = "Artist"
    )

    @Test
    fun publishesWhenSessionAndMetadataAreReady() {
        assertEquals(
            SpotifyPendingPublicationPolicy.Decision.PUBLISH,
            SpotifyPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
        assertEquals(
            SpotifyPendingPublicationPolicy.Decision.PENDING,
            SpotifyPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = false,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun hintedCaptureDropsDifferentTrack() {
        assertEquals(
            SpotifyPendingPublicationPolicy.Decision.DROP_STALE,
            SpotifyPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track.copy(id = "spotify:track:2"),
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun storeContainsAtMostOnePublication() {
        val store = SpotifyPendingPublicationStore()
        val first = SpotifyPublication("", "", emptyList(), track)
        val second = SpotifyPublication("", "", emptyList(), track)
        assertNull(store.replace(first))
        assertSame(first, store.replace(second))
        assertFalse(store.takeIfSame(first))
        assertTrue(store.takeIfSame(second))
        assertNull(store.peek())
    }
}
