/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

/**
 * NetEase normally publishes the plain numeric song id as MEDIA_ID. When a car head unit
 * (OPlus CarLink / HiCar media browser) starts playback through `playFromMediaId`, the session
 * instead carries a browse id whose last token is the song id, e.g. `other_id__1927432302`
 * or `playlist_id_86192403_26129421`, while the lyric pipeline and the `lyricInfo` payload
 * still use the plain song id. Identity checks must compare the song id.
 */
object NeteaseMediaIdPolicy {
    private const val BROWSE_ID_MARKER = "_id_"

    fun normalize(mediaId: String?): String? {
        val value = mediaId?.trim()
        if (value.isNullOrEmpty()) return null
        if (!value.contains(BROWSE_ID_MARKER)) return value
        val songId = value.substringAfterLast('_')
        return if (songId.isNotEmpty() && songId.all { it in '0'..'9' }) songId else value
    }
}
