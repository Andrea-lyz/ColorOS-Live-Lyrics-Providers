/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

/**
 * NetEase normally publishes the plain numeric song id as MEDIA_ID. When a car head unit
 * (OPlus CarLink / HiCar media browser) starts playback through `playFromMediaId`, the session
 * instead carries the browse id, e.g. `other_id__1927432302`, while the lyric pipeline and the
 * `lyricInfo` payload still use `1927432302`. Identity checks must compare the song id.
 */
object NeteaseMediaIdPolicy {
    private const val BROWSE_ID_SEPARATOR = "__"

    fun normalize(mediaId: String?): String? {
        val value = mediaId?.trim()
        if (value.isNullOrEmpty()) return null
        val songId = value.substringAfterLast(BROWSE_ID_SEPARATOR, missingDelimiterValue = "")
        return if (songId.isNotEmpty() && songId.all { it in '0'..'9' }) songId else value
    }
}
