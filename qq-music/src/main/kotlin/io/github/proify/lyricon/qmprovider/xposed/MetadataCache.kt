/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private val map = mutableMapOf<String, Metadata>()

    @Synchronized
    fun save(metadata: MediaMetadata): Metadata? {
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (id.isNullOrBlank()) return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val existing = map[id]
        val data = Metadata(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: existing?.title,
            artist = artist?.takeIf { it.isNotBlank() } ?: existing?.artist,
            duration = duration.takeIf { it > 0L } ?: existing?.duration ?: 0L
        )
        map[id] = data
        return data
    }

    @Synchronized
    fun get(id: String): Metadata? = map[id]
}

@Serializable
data class Metadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long
)
