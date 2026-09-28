/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

/**
 * Metrolist publishes Coil artwork onto MediaSession. Overlay lyricInfo only after a
 * plausible bitmap exists, or when the host has no artwork URI at all.
 */
object MetrolistArtworkPolicy {
    const val MIN_EDGE_PX = 8

    fun isPlausibleBitmapSize(width: Int, height: Int): Boolean =
        width >= MIN_EDGE_PX && height >= MIN_EDGE_PX

    fun isReadyForLyricInfo(
        hasPlausibleBitmap: Boolean,
        artworkUris: Iterable<String?>
    ): Boolean {
        if (hasPlausibleBitmap) return true
        return artworkUris.none { !it.isNullOrBlank() }
    }
}

