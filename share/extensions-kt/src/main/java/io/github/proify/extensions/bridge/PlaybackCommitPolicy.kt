/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import kotlin.math.max

data class PlaybackTrackToken(
    val mediaId: String,
    val generation: Long,
    val sessionIdentity: Int
) {
    val isValid: Boolean
        get() = mediaId.isNotBlank() && generation > 0L
}

data class PlaybackPositionSnapshot(
    val position: Long,
    val speed: Float,
    val lastPositionUpdateTime: Long,
    val moving: Boolean,
    val track: PlaybackTrackToken,
    val observedAtElapsedMillis: Long
)

object PlaybackCommitPolicy {
    private const val MAX_PROJECTION_MS = 24L * 60L * 60L * 1000L

    fun nextGeneration(current: Long, nowElapsedMillis: Long): Long {
        return max(current + 1L, nowElapsedMillis.coerceAtLeast(1L))
    }

    fun acceptsResult(
        current: PlaybackTrackToken?,
        requested: PlaybackTrackToken?,
        responseMediaId: String?
    ): Boolean {
        if (current == null || requested == null || !current.isValid || !requested.isValid) {
            return false
        }
        val responseId = responseMediaId?.takeIf { it.isNotBlank() } ?: return false
        return current == requested && responseId == requested.mediaId
    }

    fun acceptsPlayback(
        current: PlaybackTrackToken?,
        sessionIdentity: Int
    ): Boolean {
        return current?.isValid == true && current.sessionIdentity == sessionIdentity
    }

    fun projectPosition(
        snapshot: PlaybackPositionSnapshot,
        nowElapsedMillis: Long,
        duration: Long
    ): Long? {
        if (snapshot.position < 0L) return null
        var projected = snapshot.position
        if (snapshot.moving &&
            snapshot.lastPositionUpdateTime > 0L &&
            nowElapsedMillis > snapshot.lastPositionUpdateTime
        ) {
            val elapsed = (nowElapsedMillis - snapshot.lastPositionUpdateTime)
                .coerceAtMost(MAX_PROJECTION_MS)
            projected += (elapsed * snapshot.speed).toLong()
        }
        projected = projected.coerceAtLeast(0L)
        return if (duration > 0L) projected.coerceAtMost(duration) else projected
    }
}
