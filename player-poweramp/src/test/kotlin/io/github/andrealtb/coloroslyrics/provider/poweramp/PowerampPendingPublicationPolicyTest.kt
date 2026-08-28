/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PowerampPendingPublicationPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")

    @Test
    fun waitsForHostArtworkThenPublishes() {
        assertEquals(
            PowerampPendingPublicationPolicy.Decision.PENDING,
            PowerampPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = false
            )
        )
        assertEquals(
            PowerampPendingPublicationPolicy.Decision.PUBLISH,
            PowerampPendingPublicationPolicy.decide(
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
    fun hintedCaptureDropsDifferentTrack() {
        assertEquals(
            PowerampPendingPublicationPolicy.Decision.DROP_STALE,
            PowerampPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track.copy(id = "2"),
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun storeContainsAtMostOnePublication() {
        val store = PowerampPendingPublicationStore()
        val first = PowerampPublication("[00:01.00]A", "", emptyList(), track)
        val second = PowerampPublication("[00:01.00]B", "", emptyList(), track)
        assertNull(store.replace(first))
        assertSame(first, store.replace(second))
        assertFalse(store.takeIfSame(first))
        assertTrue(store.takeIfSame(second))
        assertNull(store.peek())
    }
}
