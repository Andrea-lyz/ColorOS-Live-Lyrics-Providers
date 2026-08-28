/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher

object LxNativePublisher {

    fun publish(
        session: MediaSession?,
        metadata: MediaMetadata?,
        publication: LxPublication,
        track: TrackIdentity,
        trackGeneration: Long,
        generationPolicy: TrackGenerationPolicy,
        registry: LxMediaSessionRegistry,
        hostPackage: String
    ): NativeLyricInfoPublisher.Result {
        if (session == null) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        val live = session.controller.metadata ?: metadata
        if (live == null) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        if (!LxMetadataArtwork.isReadyForLyricInfo(live)) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        LxArtworkDiagnostics.log("PUBLISH_BASE", live, session, trackGeneration)
        if (!publication.lines.any { !it.text.isNullOrBlank() }) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = "provider/lx",
                    area = "publisher",
                    event = "EMPTY_LYRIC_SKIPPED",
                    generation = trackGeneration
                )
            )
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        val hostTrack = LxMediaSessionRegistry.trackFrom(live)
        val resolvedHost = LxBluetoothLyricMetadataPolicy.resolve(track, hostTrack)?.track ?: hostTrack
        if (resolvedHost != null &&
            !LxBluetoothLyricMetadataPolicy.sameSong(resolvedHost, track) &&
            !TrackIdentityPolicy.isSameTrack(resolvedHost, track)
        ) {
            return NativeLyricInfoPublisher.Result.STALE_GENERATION
        }

        val prepared = LxMetadataArtwork.prepareForLyricInfo(live, track)
        val builder = LxMetadataArtwork.newPreservingBuilder(prepared)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = prepared,
            track = track,
            lines = publication.lines,
            trackGeneration = trackGeneration,
            generationPolicy = generationPolicy,
            playerPackage = hostPackage,
            hostPackage = hostPackage
        )
        if (result.isPublished) {
            val patched = builder.build()
            LxArtworkDiagnostics.log("PUBLISH_CANDIDATE", patched, session, trackGeneration)
            val committed = runCatching {
                registry.withModuleWrite { session.setMetadata(patched) }
            }.isSuccess
            val commitResult = classifyCommit(result, committed)
            if (commitResult.isPublished) {
                StructuredDiagnostics.logInfo(
                    DiagnosticEvent(
                        component = "provider/lx",
                        area = "publisher",
                        event = "NATIVE_LYRIC_INFO_COMMITTED",
                        generation = trackGeneration,
                        reason = "session=main lyricInfo=" + patched.getString("lyricInfo")?.length
                    )
                )
            } else {
                StructuredDiagnostics.logError(
                    DiagnosticEvent(
                        component = "provider/lx",
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
        snapshot: LxReplaySnapshot,
        generationPolicy: TrackGenerationPolicy,
        hostPackage: String
    ): Pair<NativeLyricInfoPublisher.Result, MediaMetadata?> {
        if (!LxMetadataArtwork.isReadyForLyricInfo(metadata)) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT to null
        }
        LxArtworkDiagnostics.log("REPLAY_BASE", metadata, null, snapshot.generation)
        val prepared = LxMetadataArtwork.prepareForLyricInfo(metadata, snapshot.track)
        val builder = LxMetadataArtwork.newPreservingBuilder(prepared)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = prepared,
            track = snapshot.track,
            lines = snapshot.publication.lines,
            trackGeneration = snapshot.generation,
            generationPolicy = generationPolicy,
            playerPackage = hostPackage,
            hostPackage = hostPackage
        )
        val patched = if (result.isPublished) builder.build() else null
        LxArtworkDiagnostics.log("REPLAY_CANDIDATE", patched, null, snapshot.generation)
        return result to patched
    }

    internal fun classifyCommit(
        publicationResult: NativeLyricInfoPublisher.Result,
        sessionCommitSucceeded: Boolean
    ): NativeLyricInfoPublisher.Result = if (
        publicationResult.isPublished && !sessionCommitSucceeded
    ) NativeLyricInfoPublisher.Result.COMMIT_FAILED else publicationResult
}
