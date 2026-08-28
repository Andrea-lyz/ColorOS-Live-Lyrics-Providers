/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import kotlin.math.max

/**
 * Binder limits for TrackPlayer Glide bitmaps already on MediaSession.
 *
 * Cover itself stays on LX `player.isShowNotificationImage` →
 * `updateNowPlayingMetadata({ artwork })` → Glide `ALBUM_ART`. This policy only
 * decides when that existing bitmap must be redrawn as software pixels so ColorOS
 * can read it across Binder. It does not fetch, snapshot, or invent artwork.
 */
object LxArtworkPolicy {
    const val MIN_EDGE_PX = 8
    const val MAX_SESSION_EDGE_PX = 240

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

    fun shouldCopyForBinder(configName: String?, width: Int, height: Int): Boolean {
        if (!isPlausibleBitmapSize(width, height)) return false
        if (configName == "HARDWARE") return true
        return max(width, height) > MAX_SESSION_EDGE_PX
    }

    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_SESSION_EDGE_PX): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val longest = max(width, height)
        while (longest / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    private fun uriScheme(uri: String?): String? {
        val value = uri?.trim().orEmpty()
        val separator = value.indexOf(':')
        if (separator <= 0) return null
        return value.substring(0, separator).lowercase()
    }
}
