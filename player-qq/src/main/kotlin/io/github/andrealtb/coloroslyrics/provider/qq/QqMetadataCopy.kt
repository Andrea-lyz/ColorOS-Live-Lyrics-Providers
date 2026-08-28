/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.publisher.MetadataParcelGuard
import kotlin.math.max

internal val QQ_ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART,
    MediaMetadata.METADATA_KEY_DISPLAY_ICON
)

object QqMetadataCopy {
    const val MIN_EDGE_PX = 8
    const val MAX_SESSION_EDGE_PX = 240

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

    fun newPreservingBuilder(metadata: MediaMetadata): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
        metadata.keySet().forEach { key ->
            runCatching {
                when {
                    key in QQ_ARTWORK_BITMAP_KEYS -> {
                        val bitmap = metadata.getBitmap(key) ?: return@forEach
                        val safe = binderSafe(bitmap) ?: return@forEach
                        builder.putBitmap(key, safe)
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

    fun copyWithLyricInfo(metadata: MediaMetadata, lyricInfo: String): MediaMetadata {
        val candidate = newPreservingBuilder(metadata)
            .putString(QqPlayerConstants.METADATA_KEY_LYRIC_INFO, lyricInfo)
            .build()
        return MetadataParcelGuard.acceptOrOriginal(metadata, candidate, lyricInfo)
    }

    private fun binderSafe(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        if (!isPlausible(bitmap.width, bitmap.height)) return null
        if (!shouldCopyForBinder(bitmap.config?.name, bitmap.width, bitmap.height)) {
            return bitmap
        }
        val sample = sampleSize(bitmap.width, bitmap.height)
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
        }.getOrNull()
    }

    fun isPlausible(width: Int, height: Int): Boolean =
        width >= MIN_EDGE_PX && height >= MIN_EDGE_PX

    fun shouldCopyForBinder(configName: String?, width: Int, height: Int): Boolean {
        if (!isPlausible(width, height)) return false
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
