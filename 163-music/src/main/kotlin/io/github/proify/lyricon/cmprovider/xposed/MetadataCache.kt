/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private val map = mutableMapOf<Long, Metadata>()

    @Synchronized
    fun save(metadata: MediaMetadata): Metadata? {
        val id =
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.toLongOrNull() ?: return null
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

    /**
     * Extract metadata from Netease's PlayService callback. Both supplied
     * APKs still expose PlayService.onMetadataChanged(BizMusicMeta), but the
     * concrete BizMusicMeta class differs. Reflection is kept here so the
     * adapter does not depend on either player's private model.
     */
    @Synchronized
    fun saveBiz(value: Any?): Metadata? {
        if (value == null) return null
        val outer = invoke(value, "getOuterData") ?: value
        val id = readLong(value, "getMatchedMusicId", "getMusicId", "getId")
            ?.takeIf { it > 0L }
            ?: readLong(outer, "getMatchedMusicId", "getMusicId", "getId")
                ?.takeIf { it > 0L }
            ?: return null

        val existing = map[id]
        val data = Metadata(
            id = id,
            title = readString(value, "getMusicName", "getTitle", "getName")
                ?: readString(outer, "getMusicName", "getTitle", "getName")
                ?: existing?.title,
            artist = readString(value, "getArtistsName", "getArtist")
                ?: readString(outer, "getArtistsName", "getArtist")
                ?: existing?.artist,
            duration = readLong(value, "getDuration")
                ?: readLong(outer, "getDuration")
                ?: existing?.duration
                ?: 0L
        )
        map[id] = data
        return data
    }

    @Synchronized
    fun get(id: Long): Metadata? = map[id]

    private fun invoke(value: Any?, name: String): Any? {
        if (value == null) return null
        return runCatching {
            value.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }?.invoke(value)
        }.getOrNull()
    }

    private fun readLong(value: Any?, vararg names: String): Long? {
        for (name in names) {
            when (val result = invoke(value, name)) {
                is Number -> return result.toLong()
                is String -> result.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun readString(value: Any?, vararg names: String): String? {
        for (name in names) {
            val result = invoke(value, name)?.toString()?.trim()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }
}

@Serializable
data class Metadata(
    val id: Long,
    val title: String?,
    val artist: String?,
    val duration: Long
)
