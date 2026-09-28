/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

/**
 * Readiness of TrackPlayer Glide artwork already on MediaSession.
 *
 * Cover itself stays on LX `player.isShowNotificationImage` →
 * `updateNowPlayingMetadata({ artwork })` → Glide `ALBUM_ART`. This policy only
 * decides whether a host write already carries displayable artwork before lyricInfo
 * is appended to it. It does not fetch, snapshot, redraw, or invent artwork.
 */
object LxArtworkPolicy {
    const val MIN_EDGE_PX = 8

    fun isPlausibleBitmapSize(width: Int, height: Int): Boolean =
        width >= MIN_EDGE_PX && height >= MIN_EDGE_PX

    fun isReadyForLyricInfo(
        hasPlausibleBitmap: Boolean,
        artworkUris: Iterable<String?>
    ): Boolean {
        if (hasPlausibleBitmap) return true
        val values = artworkUris.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        if (values.isEmpty()) return true
        return values.mapNotNull(::uriScheme).any {
            it == "content" || it == "android.resource" || it == "file"
        }
    }

    private fun uriScheme(uri: String?): String? {
        val value = uri?.trim().orEmpty()
        val separator = value.indexOf(':')
        if (separator <= 0) return null
        return value.substring(0, separator).lowercase()
    }
}
