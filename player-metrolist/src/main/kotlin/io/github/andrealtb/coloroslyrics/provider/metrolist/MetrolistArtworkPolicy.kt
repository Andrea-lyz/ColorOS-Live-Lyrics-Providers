/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import kotlin.math.max

/**
 * Metrolist publishes Coil artwork onto MediaSession. Overlay lyricInfo only after a
 * plausible bitmap exists, or when the host has no artwork URI at all.
 */
object MetrolistArtworkPolicy {
    const val MIN_EDGE_PX = 8
    const val MAX_SESSION_EDGE_PX = 240

    fun isPlausibleBitmapSize(width: Int, height: Int): Boolean =
        width >= MIN_EDGE_PX && height >= MIN_EDGE_PX

    fun isReadyForLyricInfo(
        hasPlausibleBitmap: Boolean,
        artworkUris: Iterable<String?>
    ): Boolean {
        if (hasPlausibleBitmap) return true
        return artworkUris.none { !it.isNullOrBlank() }
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
}

