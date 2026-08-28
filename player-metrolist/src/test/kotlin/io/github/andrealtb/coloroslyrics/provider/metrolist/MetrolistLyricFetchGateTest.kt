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
import kotlin.test.assertTrue

class MetrolistLyricFetchGateTest {
    private val complete = TrackIdentity(
        id = "sEPXrepgujY",
        title = "Style",
        artist = "Taylor Swift",
        durationMs = 231_000L
    )

    @Test
    fun requiresHostIdAndTitleBeforeSearch() {
        assertFalse(MetrolistLyricFetchGate.hasFetchableIdentity(TrackIdentity(id = "a")))
        assertFalse(MetrolistLyricFetchGate.hasFetchableIdentity(TrackIdentity(title = "Style")))
        assertFalse(MetrolistLyricFetchGate.hasFetchableIdentity(TrackIdentity(id = " ", title = "Style")))
        assertTrue(MetrolistLyricFetchGate.hasFetchableIdentity(complete))
    }

    @Test
    fun incompleteIdentityDoesNotLatchAGeneration() {
        assertFalse(
            MetrolistLyricFetchGate.shouldStartFetch(
                track = TrackIdentity(id = "sEPXrepgujY"),
                generation = 3L,
                lastFetchGeneration = null
            )
        )
        assertTrue(
            MetrolistLyricFetchGate.shouldStartFetch(
                track = complete,
                generation = 3L,
                lastFetchGeneration = null
            )
        )
    }

    @Test
    fun sameGenerationIsNotFetchedTwiceAfterBind() {
        assertFalse(
            MetrolistLyricFetchGate.shouldStartFetch(
                track = complete,
                generation = 3L,
                lastFetchGeneration = 3L
            )
        )
        assertTrue(
            MetrolistLyricFetchGate.shouldStartFetch(
                track = complete.copy(id = "_QtN6qocpKU", title = "Next"),
                generation = 4L,
                lastFetchGeneration = 3L
            )
        )
    }
}
