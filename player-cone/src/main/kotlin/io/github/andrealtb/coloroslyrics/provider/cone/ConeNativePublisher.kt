/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher

object ConeNativePublisher {

    fun publish(
        session: MediaSession?,
        metadata: MediaMetadata?,
        publication: ConePublication,
        trackGeneration: Long,
        generationPolicy: TrackGenerationPolicy,
        registry: ConeMediaSessionRegistry,
        hostPackage: String
    ): NativeLyricInfoPublisher.Result {
        if (session == null || metadata == null) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        if (!publication.lines.any { !it.text.isNullOrBlank() }) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = "provider/cone",
                    area = "publisher",
                    event = "EMPTY_LYRIC_SKIPPED",
                    generation = trackGeneration,
                    reason = publication.source.name
                )
            )
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        val track = publication.trackIdentity()
        val hostTrack = ConeMediaSessionRegistry.trackFrom(metadata)
        if (hostTrack != null && !TrackIdentityPolicy.isSameTrack(hostTrack, track)) {
            return NativeLyricInfoPublisher.Result.STALE_GENERATION
        }
        val effectiveTrack = if (!track.isBlank) track else hostTrack ?: return NativeLyricInfoPublisher.Result.INVALID_INPUT

        val builder = MediaMetadata.Builder(metadata)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = metadata,
            track = effectiveTrack,
            lines = publication.lines,
            trackGeneration = trackGeneration,
            generationPolicy = generationPolicy,
            playerPackage = hostPackage,
            hostPackage = hostPackage
        )
        if (result.isPublished) {
            val patched = builder.build()
            val committed = runCatching {
                registry.withModuleWrite { session.setMetadata(patched) }
            }.isSuccess
            val commitResult = classifyCommit(result, committed)
            if (commitResult.isPublished) {
                StructuredDiagnostics.logInfo(
                    DiagnosticEvent(
                        component = "provider/cone",
                        area = "publisher",
                        event = "NATIVE_LYRIC_INFO_COMMITTED",
                        generation = trackGeneration,
                        reason = "session=main lyricInfo=" + patched.getString("lyricInfo")?.length
                    )
                )
            } else {
                StructuredDiagnostics.logError(
                    DiagnosticEvent(
                        component = "provider/cone",
                        area = "publisher",
                        event = "SESSION_COMMIT_FAILED",
                        generation = trackGeneration,
                        reason = "setMetadata"
                    )
                )
                return commitResult
            }
        }
        return result
    }

    internal fun buildReplayMetadata(
        metadata: MediaMetadata,
        snapshot: ConeReplaySnapshot,
        generationPolicy: TrackGenerationPolicy,
        hostPackage: String
    ): Pair<NativeLyricInfoPublisher.Result, MediaMetadata?> {
        val builder = MediaMetadata.Builder(metadata)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = metadata,
            track = snapshot.track,
            lines = snapshot.publication.lines,
            trackGeneration = snapshot.generation,
            generationPolicy = generationPolicy,
            playerPackage = hostPackage,
            hostPackage = hostPackage
        )
        return result to if (result.isPublished) builder.build() else null
    }

    internal fun classifyCommit(
        publicationResult: NativeLyricInfoPublisher.Result,
        sessionCommitSucceeded: Boolean
    ): NativeLyricInfoPublisher.Result = if (
        publicationResult.isPublished && !sessionCommitSucceeded
    ) NativeLyricInfoPublisher.Result.COMMIT_FAILED else publicationResult
}
