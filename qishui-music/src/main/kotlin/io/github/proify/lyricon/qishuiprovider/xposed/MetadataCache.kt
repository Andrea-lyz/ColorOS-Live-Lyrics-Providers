/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable
import java.util.LinkedHashMap

object MetadataCache {
    private const val MAX_ENTRIES = 64
    private val map = object : LinkedHashMap<String, Metadata>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Metadata>?
        ): Boolean = size > MAX_ENTRIES
    }

    fun resolveId(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        val description = metadata.description
        return metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).takeIfNotBlank()
            ?: description.mediaId.takeIfNotBlank()
    }

    @Synchronized
    fun save(metadata: MediaMetadata?, idOverride: String? = null): Metadata? {
        if (metadata == null) return null
        val id = idOverride.takeIfNotBlank() ?: resolveId(metadata)
        if (id.isNullOrBlank()) return null

        val description = metadata.description
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: description.title?.toString()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: description.subtitle?.toString()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val previous = map[id]
        val data = Metadata(
            id = id,
            title = title.takeIfNotBlank() ?: previous?.title,
            artist = artist.takeIfNotBlank() ?: previous?.artist,
            duration = if (duration > 0L) duration else previous?.duration ?: 0L
        )
        map[id] = data
        return data
    }

    @Synchronized
    fun get(id: String): Metadata? = map[id]

    private fun String?.takeIfNotBlank(): String? =
        this?.takeIf { it.isNotBlank() }
}

@Serializable
data class Metadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long
)
