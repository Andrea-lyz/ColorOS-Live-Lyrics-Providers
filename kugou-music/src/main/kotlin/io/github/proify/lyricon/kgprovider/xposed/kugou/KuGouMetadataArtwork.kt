/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import android.media.MediaMetadata

private val KUGOU_ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON
)

private val KUGOU_ARTWORK_URI_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

internal fun MediaMetadata.hasKuGouArtwork(): Boolean {
    return hasKuGouArtworkBitmap() || KUGOU_ARTWORK_URI_KEYS.any {
        !getString(it).isNullOrBlank()
    }
}

internal fun MediaMetadata.hasKuGouArtworkBitmap(): Boolean {
    return KUGOU_ARTWORK_BITMAP_KEYS.any { getBitmap(it) != null }
}

internal fun MediaMetadata.Builder.copyKuGouArtworkFrom(
    source: MediaMetadata
): MediaMetadata.Builder {
    KUGOU_ARTWORK_BITMAP_KEYS.forEach { key ->
        source.getBitmap(key)?.let { putBitmap(key, it) }
    }
    KUGOU_ARTWORK_URI_KEYS.forEach { key ->
        source.getString(key)?.takeIf { it.isNotBlank() }?.let { putString(key, it) }
    }
    return this
}
