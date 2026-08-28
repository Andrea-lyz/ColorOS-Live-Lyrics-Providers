/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

data class AppleCachedPlaybackItem(
    val identity: TrackIdentity,
    val item: Any
)

/**
 * Queue preload emits PlaybackItems for the next song before MediaSession
 * authority catches up. Title-only metadata (no adamId yet) is already
 * authority: a differently titled queue neighbor must not steal generation.
 * `lyrics-log-20260828-004404.txt` published Look What You Made Me Do onto
 * an I Knew It, I Knew You session because blank adamId was treated as
 * "follow anything". Do not prefetch TTML on the dedicated ViewModel —
 * one in-flight `loadLyrics` cancels the current song.
 */
object AppleTrackBindPolicy {
    fun unnamedOrSame(authority: TrackIdentity?, candidate: TrackIdentity): Boolean {
        if (candidate.isBlank) return false
        if (authority == null || authority.isBlank) return true
        return TrackIdentityPolicy.isSameTrack(authority, candidate)
    }

    fun shouldFollowObservedPlaybackItem(
        authoritative: TrackIdentity?,
        observed: TrackIdentity
    ): Boolean = !observed.id.isNullOrBlank() && unnamedOrSame(authoritative, observed)

    fun hasRequestableIdentity(track: TrackIdentity): Boolean =
        !track.id.isNullOrBlank() || !track.title.isNullOrBlank()

    fun findCachedPlaybackItem(
        track: TrackIdentity,
        records: Map<String, AppleCachedPlaybackItem>
    ): AppleCachedPlaybackItem? {
        val id = track.id?.trim().orEmpty()
        if (id.isNotBlank()) {
            records[id]?.let { return it }
        }
        if (track.title.isNullOrBlank()) return null
        return records.values.firstOrNull { cached ->
            TrackIdentityPolicy.isSameTrack(cached.identity, track)
        }
    }
}
