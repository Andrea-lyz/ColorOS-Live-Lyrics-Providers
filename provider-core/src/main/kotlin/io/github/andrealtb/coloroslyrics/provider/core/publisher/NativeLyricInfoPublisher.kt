/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.publisher

import android.media.MediaMetadata
import android.os.Bundle
import android.os.Parcel
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

object NativeLyricInfoPublisher {

    const val MAX_PARCEL_BYTES = 512 * 1024 // 512 KiB
    const val MAX_LYRIC_FIELD_CHARS = 1_500_000

    /**
     * Publishes lyricInfo into the platform MediaMetadata.Builder.
     * Preserves all existing metadata, checks generation, and fails open if parcel size limits are exceeded.
     *
     * @return true if lyricInfo was safely added, false if rejected or failed-open.
     */
    fun publishToPlatformMetadata(
        builder: MediaMetadata.Builder,
        originalMetadata: MediaMetadata?,
        track: TrackIdentity,
        lines: List<RichLyricLine>,
        trackGeneration: Long,
        playerPackage: String
    ): Boolean {
        if (track.isBlank || lines.isEmpty()) {
            StructuredDiagnostics.logDebug("publish-empty") {
                "Skipping lyric publication: track is blank or lines are empty."
            }
            return false
        }

        val encoded = ColorOSLyricJsonEncoder.encode(track, lines, trackGeneration, playerPackage)
        if (encoded == null) {
            StructuredDiagnostics.logWarning("publish-encode-failed") {
                "Failed to encode lyric payload for track: ${track.buildStableKey()}"
            }
            return false
        }

        if (encoded.jsonValue.length > MAX_LYRIC_FIELD_CHARS) {
            StructuredDiagnostics.logWarning("publish-oversized-chars") {
                "Lyric JSON length (${encoded.jsonValue.length}) exceeds MAX_LYRIC_FIELD_CHARS ($MAX_LYRIC_FIELD_CHARS). Rejecting fail-open."
            }
            return false
        }

        builder.putString(ColorOSLyricJsonEncoder.METADATA_KEY_LYRIC_INFO, encoded.jsonValue)

        // Safety check: ensure metadata parcel size does not exceed limit
        val testMetadata = runCatching { builder.build() }.getOrNull()
        if (testMetadata != null && isParcelOversized(testMetadata)) {
            StructuredDiagnostics.logWarning("publish-oversized-parcel") {
                "Metadata with lyricInfo exceeds MAX_PARCEL_BYTES ($MAX_PARCEL_BYTES). Rejecting fail-open to preserve original metadata."
            }
            // Rebuild builder from original metadata without lyricInfo to fail-open
            return false
        }

        StructuredDiagnostics.logInfo("publish-success") {
            "Successfully published lyricInfo for ${track.buildStableKey()} (gen=$trackGeneration, lines=${lines.size})"
        }
        return true
    }

    private fun isParcelOversized(metadata: MediaMetadata): Boolean {
        var parcel: Parcel? = null
        return try {
            parcel = Parcel.obtain()
            metadata.writeToParcel(parcel, 0)
            parcel.dataSize() > MAX_PARCEL_BYTES
        } catch (_: Exception) {
            false
        } finally {
            parcel?.recycle()
        }
    }
}
