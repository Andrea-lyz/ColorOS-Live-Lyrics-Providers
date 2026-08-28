/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyLyricFetchGateTest {
    private val complete = TrackIdentity(
        id = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
        title = "Look What You Made Me Do",
        artist = "Taylor Swift"
    )

    @Test
    fun requiresSpotifyTrackUriBeforeSearch() {
        assertFalse(SpotifyTrackBindPolicy.hasFetchableIdentity(TrackIdentity(title = "Look")))
        assertFalse(
            SpotifyTrackBindPolicy.hasFetchableIdentity(
                TrackIdentity(id = "spotify:episode:abc", title = "Podcast")
            )
        )
        assertTrue(SpotifyTrackBindPolicy.hasFetchableIdentity(complete))
    }

    @Test
    fun incompleteIdentityDoesNotLatchAGeneration() {
        assertFalse(
            SpotifyLyricFetchGate.shouldStartFetch(
                track = TrackIdentity(title = "Look What You Made Me Do"),
                generation = 3L,
                lastFetchGeneration = null
            )
        )
        assertTrue(
            SpotifyLyricFetchGate.shouldStartFetch(
                track = complete,
                generation = 3L,
                lastFetchGeneration = null
            )
        )
    }

    @Test
    fun sameGenerationIsNotFetchedTwiceAfterBind() {
        assertFalse(
            SpotifyLyricFetchGate.shouldStartFetch(
                track = complete,
                generation = 3L,
                lastFetchGeneration = 3L
            )
        )
        assertTrue(
            SpotifyLyricFetchGate.shouldStartFetch(
                track = complete.copy(id = "spotify:track:next"),
                generation = 4L,
                lastFetchGeneration = 3L
            )
        )
    }

    @Test
    fun missingHeadersUnlatchTheGenerationForALaterRetry() {
        assertTrue(
            SpotifyLyricFetchGate.shouldUnlatchAfterMissingHeaders(
                SpotifyFetchOutcome.HeadersMissing
            )
        )
        assertFalse(
            SpotifyLyricFetchGate.shouldUnlatchAfterMissingHeaders(SpotifyFetchOutcome.NoLyric)
        )
        assertTrue(
            SpotifyLyricFetchGate.shouldStartFetch(
                track = complete,
                generation = 3L,
                lastFetchGeneration = null
            )
        )
    }
}
