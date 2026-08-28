/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConePendingPublicationPolicyTest {

    private val track1 = TrackIdentity(id = "1", title = "Song A", artist = "Artist A")
    private val track2 = TrackIdentity(id = "2", title = "Song B", artist = "Artist B")

    @Test
    fun decide_whenSessionAndMetadataReady_returnsPublish() {
        val decision = ConePendingPublicationPolicy.decide(
            publicationTrack = track1,
            currentHostTrack = track1,
            generationValid = true,
            uniqueSessionReady = true,
            metadataReady = true
        )
        assertEquals(ConePendingPublicationPolicy.Decision.PUBLISH, decision)
    }

    @Test
    fun decide_whenSessionOrMetadataNotReady_returnsPending() {
        val noSession = ConePendingPublicationPolicy.decide(
            publicationTrack = track1,
            currentHostTrack = track1,
            generationValid = true,
            uniqueSessionReady = false,
            metadataReady = true
        )
        assertEquals(ConePendingPublicationPolicy.Decision.PENDING, noSession)

        val noMetadata = ConePendingPublicationPolicy.decide(
            publicationTrack = track1,
            currentHostTrack = track1,
            generationValid = true,
            uniqueSessionReady = true,
            metadataReady = false
        )
        assertEquals(ConePendingPublicationPolicy.Decision.PENDING, noMetadata)
    }

    @Test
    fun decide_whenGenerationInvalidOrTrackMismatched_returnsDropStale() {
        val invalidGen = ConePendingPublicationPolicy.decide(
            publicationTrack = track1,
            currentHostTrack = track1,
            generationValid = false,
            uniqueSessionReady = true,
            metadataReady = true
        )
        assertEquals(ConePendingPublicationPolicy.Decision.DROP_STALE, invalidGen)

        val wrongTrack = ConePendingPublicationPolicy.decide(
            publicationTrack = track1,
            currentHostTrack = track2,
            generationValid = true,
            uniqueSessionReady = true,
            metadataReady = true
        )
        assertEquals(ConePendingPublicationPolicy.Decision.DROP_STALE, wrongTrack)
    }

    @Test
    fun pendingStore_handlesLifecycleCorrectly() {
        val store = ConePendingPublicationStore()
        val pub1 = ConePublication(ConeLyricSource.BROADCAST, "[00:01.00]Line 1", emptyList(), track1)
        val pub2 = ConePublication(ConeLyricSource.BROADCAST, "[00:01.00]Line 2", emptyList(), track1)

        assertNull(store.peek())
        assertNull(store.replace(pub1))
        assertEquals(pub1, store.peek())

        val prev = store.replace(pub2)
        assertEquals(pub1, prev)
        assertEquals(pub2, store.peek())

        assertFalse(store.takeIfSame(pub1))
        assertTrue(store.takeIfSame(pub2))
        assertNull(store.peek())
    }
}
