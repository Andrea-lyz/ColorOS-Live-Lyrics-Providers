/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

/**
 * Lyric search is keyed off Metrolist's host MediaMetadata (id + title), not the
 * first platform MediaSession snapshot. A generation is fetched at most once;
 * incomplete identities must not latch [lastFetchGeneration].
 */
object MetrolistLyricFetchGate {
    fun hasFetchableIdentity(track: TrackIdentity): Boolean =
        !track.id.isNullOrBlank() && !track.title.isNullOrBlank()

    fun shouldStartFetch(
        track: TrackIdentity,
        generation: Long,
        lastFetchGeneration: Long?
    ): Boolean {
        if (!hasFetchableIdentity(track)) return false
        if (generation <= 0L) return false
        return lastFetchGeneration != generation
    }
}
