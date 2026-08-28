/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics

internal val SPOTIFY_ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON
)

internal val SPOTIFY_ARTWORK_URI_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
)

internal object SpotifyMetadataArtwork {
    private val longKeys = setOf(
        MediaMetadata.METADATA_KEY_DURATION,
        MediaMetadata.METADATA_KEY_YEAR,
        MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_NUM_TRACKS,
        MediaMetadata.METADATA_KEY_DISC_NUMBER,
        MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
        SpotifyPlayerConstants.METADATA_KEY_ADVERTISEMENT,
        "android.media.metadata.DOWNLOAD_STATUS"
    )
    private val ratingKeys = setOf(
        MediaMetadata.METADATA_KEY_RATING,
        MediaMetadata.METADATA_KEY_USER_RATING
    )

    @Volatile
    private var binderSafeLogged = false

    fun hasPlausibleBitmap(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return SPOTIFY_ARTWORK_BITMAP_KEYS.any { key ->
            isPlausibleBitmap(metadata.getBitmap(key))
        }
    }

    fun isReadyForLyricInfo(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return SpotifyArtworkPolicy.isReadyForLyricInfo(
            hasPlausibleBitmap(metadata),
            SPOTIFY_ARTWORK_URI_KEYS.map(metadata::getString)
        )
    }

    fun isPlausibleBitmap(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) return false
        return SpotifyArtworkPolicy.isPlausibleBitmapSize(bitmap.width, bitmap.height)
    }

    fun toBinderSafe(bitmap: Bitmap): Bitmap? {
        if (!isPlausibleBitmap(bitmap)) return null
        if (!SpotifyArtworkPolicy.shouldCopyForBinder(bitmap.config?.name, bitmap.width, bitmap.height)) {
            return bitmap
        }
        val sample = SpotifyArtworkPolicy.sampleSize(bitmap.width, bitmap.height)
        val width = (bitmap.width / sample).coerceAtLeast(1)
        val height = (bitmap.height / sample).coerceAtLeast(1)
        return runCatching {
            val software = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(software)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, width, height),
                paint
            )
            software
        }.getOrNull()?.takeIf { isPlausibleBitmap(it) }
    }

    fun ensureBinderSafe(metadata: MediaMetadata): MediaMetadata {
        var converted: Bitmap? = null
        SPOTIFY_ARTWORK_BITMAP_KEYS.forEach { key ->
            val bitmap = metadata.getBitmap(key) ?: return@forEach
            if (!SpotifyArtworkPolicy.shouldCopyForBinder(
                    bitmap.config?.name,
                    bitmap.width,
                    bitmap.height
                )
            ) {
                return@forEach
            }
            converted = toBinderSafe(bitmap) ?: converted
        }
        val safe = converted ?: return metadata
        if (!binderSafeLogged) {
            binderSafeLogged = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "artwork",
                    event = "SPOTIFY_ARTWORK_BINDER_SAFE",
                    reason = "canvas-software"
                )
            )
        }
        return newPreservingBuilder(metadata, includeArtwork = false)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, safe)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, safe)
            .apply {
                if (metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null) {
                    putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, safe)
                }
            }
            .build()
    }

    fun prepareForLyricInfo(incoming: MediaMetadata): MediaMetadata = ensureBinderSafe(incoming)

    fun newPreservingBuilder(
        metadata: MediaMetadata,
        includeArtwork: Boolean = true,
        skipKeys: Set<String> = emptySet()
    ): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
        metadata.keySet().forEach { key ->
            if (key in skipKeys) return@forEach
            if (!includeArtwork && key in SPOTIFY_ARTWORK_BITMAP_KEYS) return@forEach
            runCatching {
                when {
                    key in SPOTIFY_ARTWORK_BITMAP_KEYS -> {
                        val bitmap = metadata.getBitmap(key)
                        if (bitmap != null && !bitmap.isRecycled) {
                            builder.putBitmap(key, bitmap)
                        }
                    }
                    key in longKeys -> builder.putLong(key, metadata.getLong(key))
                    key in ratingKeys -> metadata.getRating(key)?.let {
                        builder.putRating(key, it)
                    }
                    else -> {
                        val text = runCatching { metadata.getText(key) }.getOrNull()
                        val bitmap = runCatching { metadata.getBitmap(key) }.getOrNull()
                        val rating = runCatching { metadata.getRating(key) }.getOrNull()
                        when {
                            text != null -> builder.putText(key, text)
                            bitmap != null && !bitmap.isRecycled -> builder.putBitmap(key, bitmap)
                            rating != null -> builder.putRating(key, rating)
                            else -> builder.putLong(key, metadata.getLong(key))
                        }
                    }
                }
            }
        }
        return builder
    }

    fun stripLyricInfo(metadata: MediaMetadata): MediaMetadata =
        newPreservingBuilder(
            metadata,
            skipKeys = setOf(SpotifyPlayerConstants.METADATA_KEY_LYRIC_INFO)
        ).build()
}
