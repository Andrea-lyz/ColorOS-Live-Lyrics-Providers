/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

object AppleTrackIdentity {
    fun fromMetadata(metadata: MediaMetadata?): TrackIdentity? {
        if (metadata == null) return null
        val id = firstNotBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.getString(ApplePlayerConstants.METADATA_KEY_MEDIA_ID)
        )
        return TrackIdentity(
            id = id,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = normalizeDuration(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        ).takeUnless { it.isBlank }
    }

    fun fromPlaybackItem(playbackItem: Any?): TrackIdentity? {
        if (playbackItem == null) return null
        val id = AppleNativeCalls.callString(playbackItem, "getId")
        val title = firstNotBlank(
            AppleNativeCalls.callString(playbackItem, "getNowPlayingTitle"),
            AppleNativeCalls.callString(playbackItem, "getTitle")
        )
        val artist = firstNotBlank(
            AppleNativeCalls.callString(playbackItem, "getArtistName"),
            AppleNativeCalls.callString(playbackItem, "getNowPlayingSubtitle")
        )
        return TrackIdentity(
            id = id,
            title = title,
            artist = artist,
            durationMs = normalizeDuration(
                AppleNativeCalls.callLong(playbackItem, "getPlaybackDuration")
            )
        ).takeUnless { it.isBlank }
    }

    fun firstNotBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    fun normalizeDuration(duration: Long): Long {
        if (duration <= 0L) return 0L
        return if (duration < 24L * 60L * 60L) duration * 1000L else duration
    }
}
