/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

object SpotifyTrackBindPolicy {
    fun fromMetadata(metadata: MediaMetadata?): TrackIdentity? {
        if (metadata == null) return null
        val parsed = SpotifyTrackIdentity.parseMediaId(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        )
        if (parsed == null || !parsed.isTrack) return null
        return TrackIdentity(
            id = parsed.uri,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        ).takeUnless { it.isBlank }
    }

    fun shouldIgnoreMetadata(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return true
        if (SpotifyAdvertisementPolicy.isAdvertisement(metadata)) return true
        val parsed = SpotifyTrackIdentity.parseMediaId(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        )
        return parsed == null || !parsed.isTrack
    }

    fun hasFetchableIdentity(track: TrackIdentity): Boolean =
        SpotifyTrackIdentity.parseMediaId(track.id)?.isTrack == true
}
