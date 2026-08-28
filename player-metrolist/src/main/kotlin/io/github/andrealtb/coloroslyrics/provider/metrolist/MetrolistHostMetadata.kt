/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

internal object MetrolistHostMetadata {
    fun trackFromService(musicService: Any): TrackIdentity? {
        val metadata = currentMediaMetadata(musicService) ?: return null
        val mediaId = fieldValue(metadata, "id") as? String
        val title = fieldValue(metadata, "title") as? String
        if (mediaId.isNullOrBlank() || title.isNullOrBlank()) return null
        val artist = extractArtist(metadata)
        val album = extractAlbum(metadata)
        val durationSeconds = (fieldValue(metadata, "duration") as? Number)?.toInt()
        return TrackIdentity(
            id = mediaId.trim(),
            title = title.trim(),
            artist = artist.takeIf { it.isNotBlank() },
            album = album,
            durationMs = durationMsFromHostSeconds(durationSeconds)
        )
    }

    /**
     * Metrolist host duration is seconds. Unknown YouTube items use -1; search
     * treats non-positive values as a wildcard instead of a real 0-second song.
     */
    fun durationMsFromHostSeconds(durationSeconds: Int?): Long {
        val seconds = durationSeconds ?: 0
        if (seconds <= 0) return 0L
        return seconds.toLong() * 1_000L
    }

    fun currentMediaMetadata(musicService: Any): Any? {
        return runCatching {
            val stateFlow = fieldValue(musicService, MetrolistPlayerConstants.CURRENT_MEDIA_METADATA_FIELD)
                ?: return null
            stateFlow.javaClass.getMethod("getValue").invoke(stateFlow)
        }.getOrNull()
    }

    private fun extractArtist(metadata: Any): String {
        return runCatching {
            val artists = fieldValue(metadata, "artists") as? Iterable<*> ?: return ""
            artists.mapNotNull { artist ->
                artist?.let { fieldValue(it, "name") as? String }?.takeIf(String::isNotEmpty)
            }.joinToString(", ")
        }.getOrDefault("")
    }

    private fun extractAlbum(metadata: Any): String? {
        return runCatching {
            val album = fieldValue(metadata, "album") ?: return null
            (fieldValue(album, "title") as? String)?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun fieldValue(target: Any, fieldName: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }
}
