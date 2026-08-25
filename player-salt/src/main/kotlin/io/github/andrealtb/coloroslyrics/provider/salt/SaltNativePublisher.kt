/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher

object SaltNativePublisher {

    fun publish(
        session: MediaSession?,
        metadata: MediaMetadata?,
        publication: SaltPublication,
        trackGeneration: Long,
        generationPolicy: TrackGenerationPolicy,
        registry: SaltMediaSessionRegistry
    ): NativeLyricInfoPublisher.Result {
        if (session == null || metadata == null) {
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        if (!publication.lines.any { !it.text.isNullOrBlank() }) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = "provider/salt",
                    area = "publisher",
                    event = "EMPTY_LYRIC_SKIPPED",
                    generation = trackGeneration,
                    reason = publication.sourceName
                )
            )
            return NativeLyricInfoPublisher.Result.INVALID_INPUT
        }
        val track = TrackIdentity(
            id = publication.songId.takeIf { it.isNotBlank() },
            title = publication.title.takeIf { it.isNotBlank() },
            artist = publication.artist.takeIf { it.isNotBlank() },
            album = publication.album.takeIf { it.isNotBlank() },
            durationMs = publication.durationMs
        )
        val baseTrack = SaltBluetoothLyricRelayPolicy.resolve(metadata)?.track
        if (!TrackIdentityPolicy.isSameTrack(baseTrack, track)) {
            return NativeLyricInfoPublisher.Result.STALE_GENERATION
        }
        val builder = MediaMetadata.Builder(metadata)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = metadata,
            track = track,
            lines = publication.lines,
            trackGeneration = trackGeneration,
            generationPolicy = generationPolicy,
            playerPackage = SaltPlayerConstants.SALT_PACKAGE,
            hostPackage = SaltPlayerConstants.SALT_PACKAGE
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
                        component = "provider/salt",
                        area = "publisher",
                        event = "NATIVE_LYRIC_INFO_COMMITTED",
                        generation = trackGeneration,
                        reason = "session=main lyricInfo=" +
                            patched.getString("lyricInfo")?.length
                    )
                )
            } else {
                StructuredDiagnostics.logError(
                    DiagnosticEvent(
                        component = "provider/salt",
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
        snapshot: SaltReplaySnapshot,
        generationPolicy: TrackGenerationPolicy
    ): Pair<NativeLyricInfoPublisher.Result, MediaMetadata?> {
        val builder = MediaMetadata.Builder(metadata)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = metadata,
            track = snapshot.track,
            lines = snapshot.publication.lines,
            trackGeneration = snapshot.generation,
            generationPolicy = generationPolicy,
            playerPackage = SaltPlayerConstants.SALT_PACKAGE,
            hostPackage = SaltPlayerConstants.SALT_PACKAGE
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
