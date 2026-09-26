/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.graphics.Bitmap
import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.publisher.HostMetadataOverlay

internal val LX_ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON
)

internal val LX_ARTWORK_URI_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

/**
 * Session metadata helpers that do not own LX cover loading.
 *
 * Identity rewrite mirrors Walnut's post-metadata `updateNowPlayingTitles(name, singer)`. It and
 * lyricInfo are written into the host metadata itself through HostMetadataOverlay: rebuilding
 * metadata that still carried LX's 512x512 Glide bitmap collapsed the cover to a 1x1
 * average-color bitmap on ColorOS, so no artwork lane is ever copied, redrawn or replaced here.
 */
internal object LxMetadataArtwork {
    /**
     * Writes the stable song identity over a Bluetooth lyric projection in [metadata] itself.
     * Returns true only when a text key was actually rewritten.
     */
    fun prepareIdentityForSystemUi(metadata: MediaMetadata, track: TrackIdentity): Boolean {
        val rewrite = LxSessionIdentity.shouldRewrite(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            track.title,
            track.artist
        )
        if (!rewrite) return false
        return writeStableIdentity(metadata, track)
    }

    fun hasPlausibleBitmap(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return LX_ARTWORK_BITMAP_KEYS.any { key -> isPlausibleBitmap(metadata.getBitmap(key)) }
    }

    fun isReadyForLyricInfo(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return LxArtworkPolicy.isReadyForLyricInfo(
            hasPlausibleBitmap(metadata),
            LX_ARTWORK_URI_KEYS.map(metadata::getString)
        )
    }

    fun isPlausibleBitmap(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) return false
        return LxArtworkPolicy.isPlausibleBitmapSize(bitmap.width, bitmap.height)
    }

    private fun writeStableIdentity(metadata: MediaMetadata, track: TrackIdentity): Boolean {
        val title = track.title?.trim().orEmpty()
        val artist = track.artist?.trim().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) return false
        val writes = ArrayList<Pair<String, String>>(6)
        if (title.isNotEmpty()) {
            writes += MediaMetadata.METADATA_KEY_TITLE to title
            writes += MediaMetadata.METADATA_KEY_DISPLAY_TITLE to title
        }
        if (artist.isNotEmpty()) {
            writes += MediaMetadata.METADATA_KEY_ARTIST to artist
            writes += MediaMetadata.METADATA_KEY_ALBUM_ARTIST to artist
            writes += MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE to artist
        }
        track.album?.trim()?.takeIf { it.isNotEmpty() }?.let {
            writes += MediaMetadata.METADATA_KEY_ALBUM to it
        }
        var written = false
        for ((key, value) in writes) {
            when (HostMetadataOverlay.putText(metadata, key, value)) {
                HostMetadataOverlay.Result.WRITTEN -> written = true
                HostMetadataOverlay.Result.UNSUPPORTED -> return written
                else -> Unit
            }
        }
        return written
    }
}
