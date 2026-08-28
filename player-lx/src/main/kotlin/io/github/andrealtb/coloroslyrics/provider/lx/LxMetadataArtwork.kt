/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

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
 * Identity rewrite mirrors Walnut's post-metadata `updateNowPlayingTitles(name, singer)`.
 * Binder-safe artwork only redraws a bitmap TrackPlayer/Glide already placed on ALBUM_ART.
 */
internal object LxMetadataArtwork {
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

    fun prepareIdentityForSystemUi(incoming: MediaMetadata, track: TrackIdentity): MediaMetadata {
        val rewrite = LxSessionIdentity.shouldRewrite(
                incoming.getString(MediaMetadata.METADATA_KEY_TITLE),
                incoming.getString(MediaMetadata.METADATA_KEY_ARTIST),
                track.title,
                track.artist
            )
        if (!rewrite) return incoming

        // On this ColorOS framework, rebuilding metadata that still contains LX's 512x512
        // bitmap collapses the copied artwork to a 1x1 average-color bitmap. Normalize the
        // host bitmap first, then rewrite only the Bluetooth lyric title/artist fields.
        return writeStableIdentity(ensureBinderSafe(incoming), track)
    }

    fun prepareForLyricInfo(incoming: MediaMetadata, track: TrackIdentity): MediaMetadata {
        val safe = ensureBinderSafe(incoming)
        return if (LxSessionIdentity.shouldRewrite(
                safe.getString(MediaMetadata.METADATA_KEY_TITLE),
                safe.getString(MediaMetadata.METADATA_KEY_ARTIST),
                track.title,
                track.artist
            )
        ) writeStableIdentity(safe, track) else safe
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

    fun toBinderSafe(bitmap: Bitmap): Bitmap? {
        if (!isPlausibleBitmap(bitmap)) return null
        if (!LxArtworkPolicy.shouldCopyForBinder(bitmap.config?.name, bitmap.width, bitmap.height)) {
            return bitmap
        }
        val sample = LxArtworkPolicy.sampleSize(bitmap.width, bitmap.height)
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
        LxArtworkDiagnostics.log("BINDER_INPUT", metadata)
        var converted: Bitmap? = null
        LX_ARTWORK_BITMAP_KEYS.forEach { key ->
            val bitmap = metadata.getBitmap(key) ?: return@forEach
            if (!LxArtworkPolicy.shouldCopyForBinder(bitmap.config?.name, bitmap.width, bitmap.height)) {
                return@forEach
            }
            converted = toBinderSafe(bitmap) ?: converted
        }
        val safe = converted ?: return metadata.also {
            LxArtworkDiagnostics.log("BINDER_UNCHANGED", it)
        }
        if (!binderSafeLogged) {
            binderSafeLogged = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/lx",
                    area = "artwork",
                    event = "LX_ARTWORK_BINDER_SAFE",
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
            .also { LxArtworkDiagnostics.log("BINDER_OUTPUT", it) }
    }

    fun writeStableIdentity(metadata: MediaMetadata, track: TrackIdentity): MediaMetadata {
        val title = track.title?.trim().orEmpty()
        val artist = track.artist?.trim().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) return metadata
        val builder = newPreservingBuilder(metadata)
        if (title.isNotEmpty()) {
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, title)
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
        }
        if (artist.isNotEmpty()) {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, artist)
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
        }
        track.album?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM, it)
        }
        return builder.build()
    }

    fun newPreservingBuilder(
        metadata: MediaMetadata,
        includeArtwork: Boolean = true
    ): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
        metadata.keySet().forEach { key ->
            if (!includeArtwork && key in LX_ARTWORK_BITMAP_KEYS) return@forEach
            runCatching {
                when {
                    key in LX_ARTWORK_BITMAP_KEYS -> metadata.getBitmap(key)?.let {
                        builder.putBitmap(key, it)
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
