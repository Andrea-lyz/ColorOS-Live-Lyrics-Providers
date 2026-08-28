/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

object QishuiTrackMetadata {
    fun fromMetadata(metadata: MediaMetadata?): TrackIdentity? {
        if (metadata == null) return null
        val description = metadata.description
        val id = firstFilled(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            description.mediaId
        ) ?: return null
        return TrackIdentity(
            id = id,
            title = firstFilled(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                description.title?.toString()
            ),
            artist = firstFilled(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                description.subtitle?.toString()
            ),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                .takeIf { it > 0L } ?: 0L
        )
    }

    fun mergeMetadataFirst(metadata: TrackIdentity, internal: TrackIdentity): TrackIdentity =
        TrackIdentity(
            id = firstFilled(metadata.id, internal.id),
            title = firstFilled(metadata.title, internal.title),
            artist = firstFilled(metadata.artist, internal.artist),
            album = firstFilled(metadata.album, internal.album),
            durationMs = metadata.durationMs.takeIf { it > 0L } ?: internal.durationMs
        )

    fun isModuleOwnedLyricInfo(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.contains("\"source\":\"" + QishuiPlayerConstants.SOURCE + "\"") ||
            value.contains("\"provider\":\"" + QishuiPlayerConstants.HOST_PACKAGE + "\"")
    }

    private fun firstFilled(vararg values: String?): String? =
        values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}
