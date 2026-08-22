/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kwprovider.xposed

import android.media.MediaMetadata

internal val KUWO_ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON
)

internal val KUWO_ARTWORK_URI_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

internal fun MediaMetadata.hasKuWoArtwork(): Boolean {
    return hasKuWoArtworkBitmap() || KUWO_ARTWORK_URI_KEYS.any {
        !getString(it).isNullOrBlank()
    }
}

internal fun MediaMetadata.hasKuWoArtworkBitmap(): Boolean {
    return KUWO_ARTWORK_BITMAP_KEYS.any { getBitmap(it) != null }
}

/**
 * Read-only artwork inspection for diagnostics only. The publisher never rewrites
 * artwork lanes: KuWo's own cover fields pass through the lyricInfo overlay untouched,
 * so the lockscreen cover keeps following native metadata behavior.
 */
