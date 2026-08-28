/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MetrolistPendingPublicationPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")

    @Test
    fun waitsForHostArtworkThenPublishes() {
        assertEquals(
            MetrolistPendingPublicationPolicy.Decision.PENDING,
            MetrolistPendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = false
            )
        )
        assertEquals(
            MetrolistPendingPublicationPolicy.Decision.PUBLISH,
            MetrolistPendingPublicationPolicy.decide(
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
            MetrolistPendingPublicationPolicy.Decision.DROP_STALE,
            MetrolistPendingPublicationPolicy.decide(
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
    fun peekKeepsPendingUntilSuccessfulTake() {
        val store = MetrolistPendingPublicationStore()
        val publication = MetrolistPublication("[00:01.00]A", "", emptyList(), track)
        store.replace(publication)
        assertSame(publication, store.peek())
        assertSame(publication, store.peek())
        assertTrue(store.takeIfSame(publication))
        assertNull(store.peek())
    }

    @Test
    fun storeContainsAtMostOnePublication() {
        val store = MetrolistPendingPublicationStore()
        val first = MetrolistPublication("[00:01.00]A", "", emptyList(), track)
        val second = MetrolistPublication("[00:01.00]B", "", emptyList(), track)
        assertNull(store.replace(first))
        assertSame(first, store.replace(second))
        assertFalse(store.takeIfSame(first))
        assertTrue(store.takeIfSame(second))
        assertNull(store.peek())
    }
}

