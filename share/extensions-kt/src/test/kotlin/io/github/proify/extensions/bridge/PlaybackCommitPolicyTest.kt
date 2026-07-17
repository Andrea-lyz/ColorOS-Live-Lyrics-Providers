/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCommitPolicyTest {
    @Test
    fun generationIsMonotonicAcrossClockAndCounterChanges() {
        assertEquals(101L, PlaybackCommitPolicy.nextGeneration(100L, 50L))
        assertEquals(500L, PlaybackCommitPolicy.nextGeneration(100L, 500L))
        assertEquals(1L, PlaybackCommitPolicy.nextGeneration(0L, 0L))
    }

    @Test
    fun asyncResultRequiresExactTrackGenerationAndSession() {
        val current = PlaybackTrackToken("track-b", 8L, 22)

        assertTrue(PlaybackCommitPolicy.acceptsResult(current, current, "track-b"))
        assertFalse(
            PlaybackCommitPolicy.acceptsResult(
                current,
                PlaybackTrackToken("track-b", 7L, 22),
                "track-b"
            )
        )
        assertFalse(
            PlaybackCommitPolicy.acceptsResult(
                current,
                PlaybackTrackToken("track-b", 8L, 21),
                "track-b"
            )
        )
        assertFalse(PlaybackCommitPolicy.acceptsResult(current, current, "track-a"))
    }

    @Test
    fun playbackRequiresAuthoritativeSession() {
        val current = PlaybackTrackToken("track", 2L, 91)

        assertTrue(PlaybackCommitPolicy.acceptsPlayback(current, 91))
        assertFalse(PlaybackCommitPolicy.acceptsPlayback(current, 92))
        assertFalse(PlaybackCommitPolicy.acceptsPlayback(null, 91))
    }

    @Test
    fun positionProjectionUsesElapsedRealtimeAndClampsToDuration() {
        val track = PlaybackTrackToken("track", 4L, 7)
        val moving = PlaybackPositionSnapshot(10_000L, 1.5f, 1_000L, true, track, 1_000L)
        val paused = moving.copy(moving = false)

        assertEquals(
            13_000L,
            PlaybackCommitPolicy.projectPosition(moving, 3_000L, 20_000L)
        )
        assertEquals(
            12_000L,
            PlaybackCommitPolicy.projectPosition(moving, 3_000L, 12_000L)
        )
        assertEquals(
            10_000L,
            PlaybackCommitPolicy.projectPosition(paused, 3_000L, 20_000L)
        )
        assertNull(
            PlaybackCommitPolicy.projectPosition(moving.copy(position = -1L), 3_000L, 20_000L)
        )
    }
}
