/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher

object QishuiNativePublisher {
    fun publish(
        session: MediaSession?,
        publication: QishuiPublication,
        generation: Long,
        generationPolicy: TrackGenerationPolicy,
        registry: QishuiMediaSessionRegistry,
        hostPackage: String
    ): NativeLyricInfoPublisher.Result {
        if (session == null) return NativeLyricInfoPublisher.Result.INVALID_INPUT
        val metadata = session.controller.metadata
            ?: return NativeLyricInfoPublisher.Result.INVALID_INPUT
        val liveTrack = QishuiTrackMetadata.fromMetadata(metadata)
        if (liveTrack != null && !TrackIdentityPolicy.isSameTrack(liveTrack, publication.track)) {
            return NativeLyricInfoPublisher.Result.STALE_GENERATION
        }
        val builder = QishuiMetadataCopy.preservingBuilder(metadata)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = metadata,
            track = publication.track,
            lines = publication.lines,
            trackGeneration = generation,
            generationPolicy = generationPolicy,
            playerPackage = hostPackage,
            hostPackage = hostPackage
        )
        if (!result.isPublished) return result
        return if (runCatching {
                registry.withModuleWrite { session.setMetadata(builder.build()) }
            }.isSuccess
        ) {
            result
        } else {
            NativeLyricInfoPublisher.Result.COMMIT_FAILED
        }
    }

    fun buildReplayMetadata(
        metadata: MediaMetadata,
        snapshot: QishuiReplaySnapshot,
        generationPolicy: TrackGenerationPolicy,
        hostPackage: String
    ): Pair<NativeLyricInfoPublisher.Result, MediaMetadata?> {
        val builder = QishuiMetadataCopy.preservingBuilder(metadata)
        val result = NativeLyricInfoPublisher.publishToPlatformMetadata(
            builder = builder,
            originalMetadata = metadata,
            track = snapshot.publication.track,
            lines = snapshot.publication.lines,
            trackGeneration = snapshot.generation,
            generationPolicy = generationPolicy,
            playerPackage = hostPackage,
            hostPackage = hostPackage
        )
        return result to if (result.isPublished) builder.build() else null
    }
}
