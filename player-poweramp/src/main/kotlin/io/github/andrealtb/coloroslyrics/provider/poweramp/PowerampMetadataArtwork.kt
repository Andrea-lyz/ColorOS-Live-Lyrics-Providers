/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics

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
 * Empty-Builder metadata copy for ColorOS. Never use MediaMetadata.Builder(existing):
 * that constructor collapses a real bitmap to a 1x1 average-color placeholder.
 */
internal object PowerampMetadataArtwork {
    private val longKeys = setOf(
        MediaMetadata.METADATA_KEY_DURATION,
        MediaMetadata.METADATA_KEY_YEAR,
        MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_NUM_TRACKS,
        MediaMetadata.METADATA_KEY_DISC_NUMBER,
        MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
        "android.media.metadata.ADVERTISEMENT",
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

    fun toBinderSafe(bitmap: Bitmap): Bitmap? {
        if (!isPlausibleBitmap(bitmap)) return null
        if (!PowerampArtworkPolicy.shouldCopyForBinder(bitmap.config?.name, bitmap.width, bitmap.height)) {
            return bitmap
        }
        val sample = PowerampArtworkPolicy.sampleSize(bitmap.width, bitmap.height)
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
        PowerampArtworkDiagnostics.log("BINDER_INPUT", metadata)
        var converted: Bitmap? = null
        POWERAMP_ARTWORK_BITMAP_KEYS.forEach { key ->
            val bitmap = metadata.getBitmap(key) ?: return@forEach
            if (!PowerampArtworkPolicy.shouldCopyForBinder(
                    bitmap.config?.name,
                    bitmap.width,
                    bitmap.height
                )
            ) {
                return@forEach
            }
            converted = toBinderSafe(bitmap) ?: converted
        }
        val safe = converted ?: return metadata.also {
            PowerampArtworkDiagnostics.log("BINDER_UNCHANGED", it)
        }
        if (!binderSafeLogged) {
            binderSafeLogged = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/poweramp",
                    area = "artwork",
                    event = "POWERAMP_ARTWORK_BINDER_SAFE",
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
            .also { PowerampArtworkDiagnostics.log("BINDER_OUTPUT", it) }
    }

    fun prepareForLyricInfo(incoming: MediaMetadata): MediaMetadata = ensureBinderSafe(incoming)

    fun newPreservingBuilder(
        metadata: MediaMetadata,
        includeArtwork: Boolean = true
    ): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
        metadata.keySet().forEach { key ->
            if (!includeArtwork && key in POWERAMP_ARTWORK_BITMAP_KEYS) return@forEach
            runCatching {
                when {
                    key in POWERAMP_ARTWORK_BITMAP_KEYS -> {
                        val bitmap = metadata.getBitmap(key)
                        if (bitmap != null && !bitmap.isRecycled) {
                            builder.putBitmap(key, bitmap)
                        }
                    }
                    key in longKeys -> builder.putLong(key, metadata.getLong(key))
                    key in ratingKeys -> metadata.getRating(key)?.let {
                        builder.putRating(key, it)
                    }
                    else -> metadata.getText(key)?.let { builder.putText(key, it) }
                }
            }
        }
        return builder
    }
}
