/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

enum class SpotifyMediaKind {
    TRACK,
    EPISODE,
    SHOW,
    OTHER
}

data class SpotifyParsedMediaId(
    val uri: String,
    val rawId: String,
    val kind: SpotifyMediaKind
) {
    val isTrack: Boolean
        get() = kind == SpotifyMediaKind.TRACK && rawId.isNotBlank()
}

object SpotifyTrackIdentity {
    fun parseMediaId(mediaId: String?): SpotifyParsedMediaId? {
        val raw = mediaId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith(SpotifyPlayerConstants.TRACK_URI_PREFIX) -> {
                val id = raw.removePrefix(SpotifyPlayerConstants.TRACK_URI_PREFIX)
                if (id.isBlank()) null
                else SpotifyParsedMediaId(raw, id, SpotifyMediaKind.TRACK)
            }
            raw.startsWith(SpotifyPlayerConstants.EPISODE_URI_PREFIX) -> {
                val id = raw.removePrefix(SpotifyPlayerConstants.EPISODE_URI_PREFIX)
                SpotifyParsedMediaId(raw, id, SpotifyMediaKind.EPISODE)
            }
            raw.startsWith(SpotifyPlayerConstants.SHOW_URI_PREFIX) -> {
                val id = raw.removePrefix(SpotifyPlayerConstants.SHOW_URI_PREFIX)
                SpotifyParsedMediaId(raw, id, SpotifyMediaKind.SHOW)
            }
            else -> SpotifyParsedMediaId(raw, raw, SpotifyMediaKind.OTHER)
        }
    }

    fun rawTrackId(mediaId: String?): String? =
        parseMediaId(mediaId)?.takeIf { it.isTrack }?.rawId
}
