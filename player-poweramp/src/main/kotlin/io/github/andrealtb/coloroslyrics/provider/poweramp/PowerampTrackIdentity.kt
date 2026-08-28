/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.media.MediaMetadata
import android.os.Bundle
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

object PowerampTrackIdentity {
    fun normalizeId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "-1") return null
        val tail = value.substringAfterLast('/')
        return tail.takeIf { it.isNotEmpty() } ?: value
    }

    fun fromBroadcast(
        id: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long
    ): TrackIdentity? = TrackIdentity(
        id = normalizeId(id),
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        artist = artist?.trim()?.takeIf { it.isNotEmpty() },
        album = album?.trim()?.takeIf { it.isNotEmpty() },
        durationMs = durationMs.coerceAtLeast(0L)
    ).takeUnless { it.isBlank }

    fun fromMetadata(metadata: MediaMetadata?): TrackIdentity? {
        if (metadata == null) return null
        return TrackIdentity(
            id = normalizeId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)),
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
                ?.takeIf { it.isNotEmpty() },
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
                ?.takeIf { it.isNotEmpty() },
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()
                ?.takeIf { it.isNotEmpty() },
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        ).takeUnless { it.isBlank }
    }

    fun longValue(value: Any?, fallback: Long): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: fallback
        else -> fallback
    }

    @Suppress("DEPRECATION")
    fun fromTrackChangedExtras(extras: Bundle?): PowerampTrackSnapshot? {
        if (extras == null) return null
        val id = longValue(extras.get("id"), -1L)
        if (id == -1L) return null
        val durationMs = longValue(extras.get("durMs"), 0L)
        val track = fromBroadcast(
            id = id.toString(),
            title = extras.getString("title"),
            artist = extras.getString("artist"),
            album = extras.getString("album"),
            durationMs = durationMs
        ) ?: return null
        return PowerampTrackSnapshot(
            track = track,
            path = extras.getString("path")?.takeIf { it.isNotBlank() }
        )
    }
}

data class PowerampTrackSnapshot(
    val track: TrackIdentity,
    val path: String?
)
