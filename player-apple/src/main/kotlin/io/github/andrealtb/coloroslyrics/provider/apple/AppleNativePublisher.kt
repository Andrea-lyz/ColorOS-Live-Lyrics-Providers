/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher

object AppleNativePublisher {

    fun publish(
        session: MediaSession?,
        metadata: MediaMetadata?,
        publication: ApplePublication,
        track: TrackIdentity,
        trackGeneration: Long,
        generationPolicy: TrackGenerationPolicy,
        registry: AppleMediaSessionRegistry,
        hostPackage: String
    ): NativeLyricInfoPublisher.Result {
        if (session == null) return NativeLyricInfoPublisher.Result.INVALID_INPUT
        val live = session.controller.metadata ?: metadata
        if (live == null) return NativeLyricInfoPublisher.Result.INVALID_INPUT
        if (!AppleMetadataArtwork.isReadyForLyricInfo(live)) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        if (!publication.lines.any { !it.text.isNullOrBlank() }) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "publisher",
                    event = "EMPTY_LYRIC_SKIPPED",
                    generation = trackGeneration,
                    reason = publication.sourceName
                )
            )
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        val hostTrack = AppleTrackIdentity.fromMetadata(live)
        if (hostTrack != null && !TrackIdentityPolicy.isSameTrack(hostTrack, track)) {
            return NativeLyricInfoPublisher.Result.STALE_GENERATION
        }

        val prepared = AppleMetadataArtwork.prepareForLyricInfo(live)
        val builder = AppleMetadataArtwork.newPreservingBuilder(prepared)
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
            val committed = runCatching {
                registry.withModuleWrite { session.setMetadata(patched) }
            }.isSuccess
            val commitResult = classifyCommit(result, committed)
            if (commitResult.isPublished) {
                StructuredDiagnostics.logInfo(
                    DiagnosticEvent(
                        component = ApplePlayerConstants.COMPONENT,
                        area = "publisher",
                        event = "NATIVE_LYRIC_INFO_COMMITTED",
                        generation = trackGeneration,
                        reason = "session=main source=" + publication.sourceName +
                            " lyricInfo=" + patched.getString("lyricInfo")?.length
                    )
                )
            } else {
                StructuredDiagnostics.logError(
                    DiagnosticEvent(
                        component = ApplePlayerConstants.COMPONENT,
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
        snapshot: AppleReplaySnapshot,
        generationPolicy: TrackGenerationPolicy,
        hostPackage: String
    ): Pair<NativeLyricInfoPublisher.Result, MediaMetadata?> {
        if (!AppleMetadataArtwork.isReadyForLyricInfo(metadata)) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT to null
        }
        val prepared = AppleMetadataArtwork.prepareForLyricInfo(metadata)
        val builder = AppleMetadataArtwork.newPreservingBuilder(prepared)
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
        return result to patched
    }

    internal fun classifyCommit(
        publicationResult: NativeLyricInfoPublisher.Result,
        sessionCommitSucceeded: Boolean
    ): NativeLyricInfoPublisher.Result = if (
        publicationResult.isPublished && !sessionCommitSucceeded
    ) NativeLyricInfoPublisher.Result.COMMIT_FAILED else publicationResult
}
