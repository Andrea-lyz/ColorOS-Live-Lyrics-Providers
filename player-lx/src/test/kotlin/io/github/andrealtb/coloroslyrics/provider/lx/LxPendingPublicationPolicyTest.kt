/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LxPendingPublicationPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")

    @Test
    fun blankCaptureWaitsForHostThenPublishes() {
        assertEquals(
            LxPendingPublicationPolicy.Decision.PENDING,
            LxPendingPublicationPolicy.decide(
                publicationTrack = TrackIdentity(),
                currentHostTrack = null,
                generationValid = false,
                uniqueSessionReady = false,
                metadataReady = false,
                artworkReady = false
            )
        )
        assertEquals(
            LxPendingPublicationPolicy.Decision.PUBLISH,
            LxPendingPublicationPolicy.decide(
                publicationTrack = TrackIdentity(),
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun hintedCaptureDropsDifferentTrackAndWaitsForReadiness() {
        assertEquals(
            LxPendingPublicationPolicy.Decision.DROP_STALE,
            LxPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track.copy(id = "2"),
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
        assertEquals(
            LxPendingPublicationPolicy.Decision.PENDING,
            LxPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = false,
                metadataReady = true,
                artworkReady = true
            )
        )
        assertEquals(
            LxPendingPublicationPolicy.Decision.PUBLISH,
            LxPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun matchingTrackWaitsForHostArtworkBitmap() {
        assertEquals(
            LxPendingPublicationPolicy.Decision.PENDING,
            LxPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = false
            )
        )
    }

    @Test
    fun storeContainsAtMostOnePublication() {
        val store = LxPendingPublicationStore()
        val first = LxPublication("[00:01.00]A", "", emptyList(), track)
        val second = LxPublication("[00:01.00]B", "", emptyList(), track)
        assertNull(store.replace(first))
        assertSame(first, store.replace(second))
        assertFalse(store.takeIfSame(first))
        assertTrue(store.takeIfSame(second))
        assertNull(store.peek())
    }
}
