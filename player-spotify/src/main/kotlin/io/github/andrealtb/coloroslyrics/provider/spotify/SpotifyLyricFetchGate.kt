/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

/**
 * Color Lyrics lookup is keyed off `spotify:track:` identity. A generation is
 * fetched at most once; ads, podcasts, and incomplete ids must not latch
 * [lastFetchGeneration].
 */
object SpotifyLyricFetchGate {
    fun shouldStartFetch(
        track: TrackIdentity,
        generation: Long,
        lastFetchGeneration: Long?
    ): Boolean {
        if (!SpotifyTrackBindPolicy.hasFetchableIdentity(track)) return false
        if (generation <= 0L) return false
        return lastFetchGeneration != generation
    }

    fun shouldUnlatchAfterMissingHeaders(outcome: SpotifyFetchOutcome): Boolean =
        outcome is SpotifyFetchOutcome.HeadersMissing
}
