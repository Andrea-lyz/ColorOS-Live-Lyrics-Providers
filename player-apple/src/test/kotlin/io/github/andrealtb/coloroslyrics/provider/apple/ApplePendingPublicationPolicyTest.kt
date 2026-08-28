/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplePendingPublicationPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")

    @Test
    fun waitsForHostArtworkThenPublishes() {
        assertEquals(
            ApplePendingPublicationPolicy.Decision.PENDING,
            ApplePendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                liveSessionTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = false
            )
        )
        assertEquals(
            ApplePendingPublicationPolicy.Decision.PUBLISH,
            ApplePendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                liveSessionTrack = track,
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
            ApplePendingPublicationPolicy.Decision.DROP_STALE,
            ApplePendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track.copy(id = "2"),
                liveSessionTrack = track,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun namedLiveSessionMismatchStaysPendingWhileHostStillOwnsTheSong() {
        val live = TrackIdentity(title = "I Knew It, I Knew You", artist = "Taylor Swift")
        assertEquals(
            ApplePendingPublicationPolicy.Decision.PENDING,
            ApplePendingPublicationPolicy.decide(
                publicationTrack = track,
                currentHostTrack = track,
                liveSessionTrack = live,
                generationValid = true,
                uniqueSessionReady = true,
                metadataReady = true,
                artworkReady = true
            )
        )
    }

    @Test
    fun peekKeepsPendingUntilSuccessfulTake() {
        val store = ApplePendingPublicationStore()
        val publication = ApplePublication(emptyList(), track)
        store.replace(publication)
        assertSame(publication, store.peek())
        assertTrue(store.takeIfSame(publication))
        assertNull(store.peek())
        assertFalse(store.takeIfSame(publication))
    }
}
