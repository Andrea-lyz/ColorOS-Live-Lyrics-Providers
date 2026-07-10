/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.os.Bundle
import kotlinx.serialization.Serializable

object TrackMetadataCache {
    private val map = mutableMapOf<String, TrackMetadata>()

    fun save(metadata: Bundle): TrackMetadata? {
        val id = metadata.longValue("id", -1L)
        if (id == -1L) return null

        val title = metadata.getString("title")
        val artist = metadata.getString("artist")
        val album = metadata.getString("album")
        // Poweramp sends durMs as an Int on some builds. Bundle#getLong logs a
        // ClassCastException and silently returns zero for that valid payload.
        val duration = metadata.longValue("durMs", 0L)
        val path = metadata.getString("path")

        val data = TrackMetadata(
            raw = metadata,
            id = id.toString(),
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            path = path
        )
        map[id.toString()] = data
        return data
    }

    fun get(id: String): TrackMetadata? = map[id]
}

internal fun bundleLong(value: Any?, fallback: Long): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: fallback
    else -> fallback
}

private fun Bundle.longValue(key: String, fallback: Long): Long =
    bundleLong(rawValue(key), fallback)

@Suppress("DEPRECATION")
private fun Bundle.rawValue(key: String): Any? = get(key)

@Serializable
data class TrackMetadata(
    val raw: Bundle,
    val id: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Long,
    val path: String?,
)
