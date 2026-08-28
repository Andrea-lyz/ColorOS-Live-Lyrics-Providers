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

class AppleTrackBindPolicyTest {
    private val iKnewIt = TrackIdentity(
        title = "I Knew It, I Knew You (From \"Toy Story 5\")",
        artist = "Taylor Swift"
    )
    private val iKnewItItem = TrackIdentity(
        id = "i-knew-it",
        title = "I Knew It, I Knew You (From \"Toy Story 5\")",
        artist = "Taylor Swift"
    )
    private val lookWhat = TrackIdentity(
        id = "look-what",
        title = "Look What You Made Me Do",
        artist = "Taylor Swift"
    )

    @Test
    fun followsObservedSongUntilMediaSessionEstablishesAuthority() {
        assertTrue(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(
                null,
                TrackIdentity(id = "queued-track")
            )
        )
        assertTrue(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(
                TrackIdentity(),
                TrackIdentity(id = "queued-track")
            )
        )
    }

    @Test
    fun followsPlaybackItemMatchingAuthoritativeMediaSessionTrack() {
        assertTrue(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(
                TrackIdentity(id = "current-track"),
                TrackIdentity(id = "current-track")
            )
        )
        assertTrue(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(iKnewIt, iKnewItItem)
        )
    }

    @Test
    fun ignoresQueueNeighborAfterMediaSessionEstablishesAuthority() {
        assertFalse(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(
                TrackIdentity(id = "current-track"),
                TrackIdentity(id = "next-track")
            )
        )
        assertFalse(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(
                TrackIdentity(id = "current-track"),
                TrackIdentity()
            )
        )
    }

    @Test
    fun titleOnlySessionDoesNotFollowQueueNeighbor() {
        assertFalse(
            AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(iKnewIt, lookWhat)
        )
        assertFalse(AppleTrackBindPolicy.unnamedOrSame(iKnewIt, lookWhat))
    }

    @Test
    fun unnamedAuthorityAcceptsAnyNamedCandidate() {
        assertTrue(AppleTrackBindPolicy.unnamedOrSame(null, lookWhat))
        assertTrue(AppleTrackBindPolicy.unnamedOrSame(TrackIdentity(), iKnewItItem))
        assertTrue(AppleTrackBindPolicy.unnamedOrSame(iKnewIt, iKnewItItem))
        assertFalse(AppleTrackBindPolicy.unnamedOrSame(iKnewIt, TrackIdentity()))
    }

    @Test
    fun findsCachedPlaybackItemByTitleWhenAdamIdIsStillBlank() {
        val records = mapOf(
            lookWhat.id!! to AppleCachedPlaybackItem(lookWhat, "look-what-item"),
            iKnewItItem.id!! to AppleCachedPlaybackItem(iKnewItItem, "i-knew-it-item")
        )
        val found = AppleTrackBindPolicy.findCachedPlaybackItem(iKnewIt, records)
        assertEquals("i-knew-it-item", found?.item)
        assertSame(iKnewItItem, found?.identity)
        assertNull(
            AppleTrackBindPolicy.findCachedPlaybackItem(
                TrackIdentity(title = "Cruel Summer", artist = "Taylor Swift"),
                records
            )
        )
    }
}
