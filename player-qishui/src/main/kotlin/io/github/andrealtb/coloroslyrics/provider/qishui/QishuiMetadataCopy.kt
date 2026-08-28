/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata

object QishuiMetadataCopy {
    private val bitmapKeys = setOf(
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON
    )
    private val longKeys = setOf(
        MediaMetadata.METADATA_KEY_DURATION,
        MediaMetadata.METADATA_KEY_YEAR,
        MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_NUM_TRACKS,
        MediaMetadata.METADATA_KEY_DISC_NUMBER,
        MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
        "android.media.metadata.DOWNLOAD_STATUS",
        "android.media.metadata.ADVERTISEMENT"
    )
    private val ratingKeys = setOf(
        MediaMetadata.METADATA_KEY_RATING,
        MediaMetadata.METADATA_KEY_USER_RATING
    )

    fun preservingBuilder(
        metadata: MediaMetadata,
        skipKeys: Set<String> = emptySet()
    ): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
        metadata.keySet().forEach { key ->
            if (key in skipKeys) return@forEach
            runCatching {
                when {
                    key in bitmapKeys -> metadata.getBitmap(key)?.let { bitmap ->
                        if (!bitmap.isRecycled) builder.putBitmap(key, binderSafe(bitmap))
                    }
                    key in longKeys -> builder.putLong(key, metadata.getLong(key))
                    key in ratingKeys -> metadata.getRating(key)?.let { builder.putRating(key, it) }
                    else -> metadata.getText(key)?.let { builder.putText(key, it) }
                }
            }
        }
        return builder
    }

    fun stripModuleLyricInfo(metadata: MediaMetadata): MediaMetadata =
        preservingBuilder(
            metadata,
            setOf(QishuiPlayerConstants.METADATA_KEY_LYRIC_INFO)
        ).build()

    private fun binderSafe(bitmap: Bitmap): Bitmap {
        val needsCopy = bitmap.config == Bitmap.Config.HARDWARE ||
            bitmap.width > 240 ||
            bitmap.height > 240
        if (!needsCopy) return bitmap
        val scale = minOf(240f / bitmap.width, 240f / bitmap.height, 1f)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            val safe = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(safe).drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            safe
        }.getOrDefault(bitmap)
    }
}
