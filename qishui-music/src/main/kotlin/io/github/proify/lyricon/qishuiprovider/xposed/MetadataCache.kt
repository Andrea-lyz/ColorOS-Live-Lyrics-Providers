/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MetadataCache {
    private val map = mutableMapOf<String, Metadata>()

    fun resolveId(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        return metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).takeIfNotBlank()
            ?: metadata.description?.mediaId.takeIfNotBlank()
    }

    fun save(metadata: MediaMetadata?, idOverride: String? = null): Metadata? {
        if (metadata == null) return null
        val id = idOverride.takeIfNotBlank() ?: resolveId(metadata)
        if (id.isNullOrBlank()) return null

        if (map.containsKey(id)) return map[id]

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.description?.title?.toString()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.description?.subtitle?.toString()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
//        metadata.keySet().forEach {
//            Log.d("MediaMetadataCache", "key: $it, value: ${metadata.getString(it)}")
//        }

        val data = Metadata(id, title, artist, if (duration > 0L) duration else 0L)
        map[id] = data
        return data
    }

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
