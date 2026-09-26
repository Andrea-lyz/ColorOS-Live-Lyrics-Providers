/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.graphics.Bitmap
import android.media.MediaMetadata

internal val POWERAMP_ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON
)

internal val POWERAMP_ARTWORK_URI_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

/**
 * Read-only artwork readiness checks on host metadata.
 *
 * lyricInfo is appended into the host metadata itself through HostMetadataOverlay, so this
 * Provider never copies, redraws or replaces an artwork lane; the cover SystemUI shows is exactly
 * the one Poweramp published.
 */
internal object PowerampMetadataArtwork {
    fun hasPlausibleBitmap(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return POWERAMP_ARTWORK_BITMAP_KEYS.any { key ->
            isPlausibleBitmap(metadata.getBitmap(key))
        }
    }

    fun isReadyForLyricInfo(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return PowerampArtworkPolicy.isReadyForLyricInfo(
            hasPlausibleBitmap(metadata),
            POWERAMP_ARTWORK_URI_KEYS.map(metadata::getString)
        )
    }

    fun isPlausibleBitmap(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) return false
        return PowerampArtworkPolicy.isPlausibleBitmapSize(bitmap.width, bitmap.height)
    }
}
