/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

/**
 * Poweramp publishes a placeholder `android.resource` URI (and a null bitmap) on
 * track change, then a second metadata write with the decoded ALBUM_ART bitmap.
 * Overlaying lyricInfo before that bitmap exists is how the old Provider wiped
 * the lockscreen cover.
 *
 * URI-only is therefore not ready. No artwork URI at all is native no-cover.
 */
object PowerampArtworkPolicy {
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
