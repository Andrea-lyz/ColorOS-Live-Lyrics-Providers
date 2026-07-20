/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MetadataCache {
    private val map = mutableMapOf<String, Metadata>()
    private var currentMetadata: Metadata? = null

    fun save(metadata: MediaMetadata?): Metadata? {
        if (metadata == null) return null
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "unknown"

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val data = Metadata(id, title, artist, if (duration == 0L) Long.MAX_VALUE else duration)
        map[id] = data
        currentMetadata = data
        return data
    }

    fun get(id: String): Metadata? = map[id]

    fun getCurrent(): Metadata? = currentMetadata
}

@Serializable
data class Metadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long
)
