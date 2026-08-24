/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.publisher

import android.media.MediaMetadata
import android.os.Parcel
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

object NativeLyricInfoPublisher {

    const val MAX_PARCEL_BYTES = 512 * 1024
    const val MAX_LYRIC_FIELD_CHARS = 1_500_000

    enum class Result {
        PUBLISHED,
        INVALID_INPUT,
        HOST_PACKAGE_MISMATCH,
        STALE_GENERATION,
        ENCODE_FAILED,
        PAYLOAD_TOO_LARGE,
        PARCEL_MEASUREMENT_FAILED,
        COMMIT_FAILED;

        val isPublished: Boolean
            get() = this == PUBLISHED
    }

    /**
     * Adds lyricInfo only after a complete candidate metadata object has passed all gates.
     * The supplied builder is therefore never mutated on a rejected publication.
     * Callers must seed [builder] and [originalMetadata] from the same host metadata snapshot.
     */
    fun publishToPlatformMetadata(
        builder: MediaMetadata.Builder,
        originalMetadata: MediaMetadata?,
        track: TrackIdentity,
        lines: List<RichLyricLine>,
        trackGeneration: Long,
        generationPolicy: TrackGenerationPolicy,
        playerPackage: String,
        hostPackage: String
    ): Result = publishTransactional(
        builder = builder,
        originalMetadata = originalMetadata,
        track = track,
        lines = lines,
        trackGeneration = trackGeneration,
        generationPolicy = generationPolicy,
        playerPackage = playerPackage,
        hostPackage = hostPackage,
        transaction = AndroidMetadataTransaction
    )

    internal fun <M, B> publishTransactional(
        builder: B,
        originalMetadata: M?,
        track: TrackIdentity,
        lines: List<RichLyricLine>,
        trackGeneration: Long,
        generationPolicy: TrackGenerationPolicy,
        playerPackage: String,
        hostPackage: String,
        transaction: MetadataTransaction<M, B>
    ): Result {
        if (originalMetadata == null || track.isBlank || lines.isEmpty()) return Result.INVALID_INPUT
        if (playerPackage.isBlank() || playerPackage != hostPackage) return Result.HOST_PACKAGE_MISMATCH
        if (!generationPolicy.isGenerationValid(trackGeneration) ||
            !TrackIdentityPolicy.isSameTrack(generationPolicy.currentTrack, track)
        ) {
            return Result.STALE_GENERATION
        }

        val encoded = runCatching {
            ColorOSLyricJsonEncoder.encode(track, lines, trackGeneration, playerPackage)
        }.getOrNull() ?: return Result.ENCODE_FAILED
        if (encoded.jsonValue.length > MAX_LYRIC_FIELD_CHARS) return Result.PAYLOAD_TOO_LARGE

        val candidate = runCatching {
            transaction.buildCandidate(
                originalMetadata,
                ColorOSLyricJsonEncoder.METADATA_KEY_LYRIC_INFO,
                encoded.jsonValue
            )
        }.getOrNull() ?: return Result.PARCEL_MEASUREMENT_FAILED

        val parcelBytes = runCatching { transaction.measureParcelBytes(candidate) }.getOrNull()
            ?: return Result.PARCEL_MEASUREMENT_FAILED
        if (parcelBytes > MAX_PARCEL_BYTES) return Result.PAYLOAD_TOO_LARGE

        val committed = runCatching {
            transaction.commit(
                builder,
                ColorOSLyricJsonEncoder.METADATA_KEY_LYRIC_INFO,
                encoded.jsonValue
            )
        }.isSuccess
        if (!committed) return Result.COMMIT_FAILED
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/${playerPackage.substringAfterLast('.')}",
                area = "publisher",
                event = "LYRIC_INFO_PUBLISHED",
                generation = trackGeneration,
                trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                payloadChars = encoded.jsonValue.length,
                parcelBytes = parcelBytes
            )
        )
        return Result.PUBLISHED
    }

    internal interface MetadataTransaction<M, B> {
        fun buildCandidate(originalMetadata: M?, key: String, value: String): M
        fun measureParcelBytes(metadata: M): Int?
        fun commit(builder: B, key: String, value: String)
    }

    private object AndroidMetadataTransaction : MetadataTransaction<MediaMetadata, MediaMetadata.Builder> {
        override fun buildCandidate(originalMetadata: MediaMetadata?, key: String, value: String): MediaMetadata {
            val candidateBuilder = originalMetadata?.let(MediaMetadata::Builder) ?: MediaMetadata.Builder()
            return candidateBuilder.putString(key, value).build()
        }

        override fun measureParcelBytes(metadata: MediaMetadata): Int? {
            var parcel: Parcel? = null
            return try {
                parcel = Parcel.obtain()
                metadata.writeToParcel(parcel, 0)
                parcel.dataSize()
            } catch (_: Throwable) {
                null
            } finally {
                parcel?.recycle()
            }
        }

        override fun commit(builder: MediaMetadata.Builder, key: String, value: String) {
            builder.putString(key, value)
        }
    }
}
