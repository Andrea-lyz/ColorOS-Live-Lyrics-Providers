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
import kotlin.test.assertTrue

class AppleLyricRequestGateTest {
    private val complete = TrackIdentity(
        id = "1440935467",
        title = "Cruel Summer",
        artist = "Taylor Swift",
        durationMs = 178_000L
    )

    @Test
    fun requiresPlaybackItemBeforeLoadLyrics() {
        val titleOnly = TrackIdentity(title = "Style", artist = "Taylor Swift")
        assertTrue(AppleTrackBindPolicy.hasRequestableIdentity(titleOnly))
        assertTrue(AppleTrackBindPolicy.hasRequestableIdentity(complete))
        assertFalse(
            AppleTrackBindPolicy.hasRequestableIdentity(TrackIdentity(artist = "Taylor Swift"))
        )
        assertFalse(
            AppleLyricRequestGate.shouldStartRequest(
                track = titleOnly,
                generation = 1L,
                lastRequestGeneration = null,
                hasPlaybackItem = false
            )
        )
        assertTrue(
            AppleLyricRequestGate.shouldStartRequest(
                track = titleOnly,
                generation = 1L,
                lastRequestGeneration = null,
                hasPlaybackItem = true
            )
        )
        assertFalse(
            AppleLyricRequestGate.shouldStartRequest(
                track = complete,
                generation = 1L,
                lastRequestGeneration = null,
                hasPlaybackItem = false
            )
        )
        assertTrue(
            AppleLyricRequestGate.shouldStartRequest(
                track = complete,
                generation = 1L,
                lastRequestGeneration = null,
                hasPlaybackItem = true
            )
        )
    }

    @Test
    fun sameGenerationIsNotRequestedTwiceUntilRetryClears() {
        assertFalse(
            AppleLyricRequestGate.shouldStartRequest(
                track = complete,
                generation = 3L,
                lastRequestGeneration = 3L,
                hasPlaybackItem = true
            )
        )
        assertTrue(
            AppleLyricRequestGate.shouldStartRequest(
                track = complete.copy(id = "next"),
                generation = 4L,
                lastRequestGeneration = 3L,
                hasPlaybackItem = true
            )
        )
    }

    @Test
    fun retriesThreeTimesWithBoundedDelays() {
        assertEquals(400L, AppleLyricRequestGate.retryDelayMs(1))
        assertEquals(1_200L, AppleLyricRequestGate.retryDelayMs(2))
        assertTrue(AppleLyricRequestGate.shouldRetry(1, hasLyrics = false))
        assertTrue(AppleLyricRequestGate.shouldRetry(2, hasLyrics = false))
        assertFalse(AppleLyricRequestGate.shouldRetry(3, hasLyrics = false))
        assertFalse(AppleLyricRequestGate.shouldRetry(1, hasLyrics = true))
    }

    @Test
    fun pollsForPlaybackItemABoundedNumberOfTimes() {
        assertTrue(AppleLyricRequestGate.shouldPollForPlaybackItem(0))
        assertTrue(AppleLyricRequestGate.shouldPollForPlaybackItem(7))
        assertFalse(AppleLyricRequestGate.shouldPollForPlaybackItem(8))
        assertEquals(200L, AppleLyricRequestGate.PLAYBACK_ITEM_POLL_DELAY_MS)
    }

    @Test
    fun metadataDurationUnderADayIsTreatedAsSeconds() {
        assertEquals(178_000L, AppleTrackIdentity.normalizeDuration(178L))
        assertEquals(178_000L, AppleTrackIdentity.normalizeDuration(178_000L))
        assertEquals(0L, AppleTrackIdentity.normalizeDuration(0L))
    }
}
