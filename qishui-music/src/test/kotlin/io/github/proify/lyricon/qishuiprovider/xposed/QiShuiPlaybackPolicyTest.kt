package io.github.proify.lyricon.qishuiprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QiShuiPlaybackPolicyTest {

    @Test
    fun onlyMainQiShuiProcessOwnsProvider() {
        assertTrue(QiShuiPlaybackPolicy.isPlaybackProcess("com.luna.music", "com.luna.music"))
        assertEquals(
            false,
            QiShuiPlaybackPolicy.isPlaybackProcess("com.luna.music", "com.luna.music:push")
        )
    }

    @Test
    fun officialQueueCandidateCannotTakeOverCurrentTrack() {
        assertTrue(
            QiShuiPlaybackPolicy.acceptsOfficialCandidate("current", 42L, "current", 42L)
        )
        assertEquals(
            false,
            QiShuiPlaybackPolicy.acceptsOfficialCandidate("current", 42L, "queued", 42L)
        )
        assertEquals(
            false,
            QiShuiPlaybackPolicy.acceptsOfficialCandidate("current", 42L, "current", 41L)
        )
        assertEquals(
            false,
            QiShuiPlaybackPolicy.acceptsOfficialCandidate(null, 42L, "queued", 42L)
        )
    }

    @Test
    fun stateObservedBeforeSongIsAvailableForSameCommit() {
        val state = snapshot(generation = 42L, session = 7)

        assertSame(state, QiShuiPlaybackPolicy.snapshotForCommit(state, 42L, 7))
    }

    @Test
    fun staleTrackOrDifferentSessionCannotBeReplayed() {
        val state = snapshot(generation = 41L, session = 7)

        assertNull(QiShuiPlaybackPolicy.snapshotForCommit(state, 42L, 7))
        assertNull(QiShuiPlaybackPolicy.snapshotForCommit(state, 41L, 8))
    }

    @Test
    fun projectsMovingPositionAndClampsToDuration() {
        val state = snapshot(
            generation = 42L,
            session = 7,
            position = 9_500L,
            updateTime = 1_000L
        )

        assertEquals(10_000L, QiShuiPlaybackPolicy.projectPosition(state, 2_000L, 10_000L))
    }

    @Test
    fun unknownPositionIsNotReplayed() {
        val state = snapshot(generation = 42L, session = 7, position = -1L)

        assertNull(QiShuiPlaybackPolicy.projectPosition(state, 2_000L, 10_000L))
    }

    @Test
    fun generationIsMonotonicAcrossFastRestarts() {
        assertEquals(1_000L, QiShuiPlaybackPolicy.nextGeneration(8L, 1_000L))
        assertEquals(1_001L, QiShuiPlaybackPolicy.nextGeneration(1_000L, 900L))
    }

    @Test
    fun playbackDiagnosticsSuppressRoutinePositionUpdates() {
        val previous = snapshot(
            generation = 42L,
            session = 7,
            position = 1_000L,
            updateTime = 1_000L
        )
        val current = snapshot(
            generation = 42L,
            session = 7,
            position = 2_000L,
            updateTime = 2_000L
        )

        assertEquals(false, QiShuiPlaybackPolicy.shouldLogPlaybackSnapshot(previous, current))
    }

    @Test
    fun playbackDiagnosticsKeepStateChangesAndSeeks() {
        val previous = snapshot(
            generation = 42L,
            session = 7,
            position = 1_000L,
            updateTime = 1_000L
        )
        val paused = snapshot(
            generation = 42L,
            session = 7,
            position = 1_500L,
            updateTime = 1_500L,
            state = 2,
            moving = false
        )
        val seeked = snapshot(
            generation = 42L,
            session = 7,
            position = 15_000L,
            updateTime = 2_000L
        )

        assertTrue(QiShuiPlaybackPolicy.shouldLogPlaybackSnapshot(previous, paused))
        assertTrue(QiShuiPlaybackPolicy.shouldLogPlaybackSnapshot(previous, seeked))
    }

    @Test
    fun resolutionRetryWindowIsBounded() {
        val delays = (0 until QiShuiResolutionPolicy.MAX_ATTEMPTS)
            .mapNotNull(QiShuiResolutionPolicy::delayBeforeAttempt)

        assertEquals(QiShuiResolutionPolicy.MAX_ATTEMPTS, delays.size)
        assertTrue(delays.sum() < 15_000L)
        assertNull(QiShuiResolutionPolicy.delayBeforeAttempt(QiShuiResolutionPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun songCommitUsesAtomicProviderAndBridgeOrder() {
        val events = mutableListOf<String>()

        dispatchQiShuiSongCommit(
            setSong = { events += "song" },
            setPosition = { events += "position" },
            replayPlaybackState = { events += "state" },
            publishLyricReady = { events += "lyricReady" },
            publishPlaybackState = { events += "bridgeState" }
        )

        assertEquals(
            listOf("song", "position", "state", "lyricReady", "bridgeState"),
            events
        )
    }

    private fun snapshot(
        generation: Long,
        session: Int,
        position: Long = 1_000L,
        updateTime: Long = 1_000L,
        state: Int = 3,
        moving: Boolean = true
    ) = QiShuiPlaybackSnapshot(
        state = state,
        position = position,
        speed = 1f,
        lastPositionUpdateTime = updateTime,
        moving = moving,
        trackGeneration = generation,
        sessionIdentity = session,
        observedAtElapsedMillis = updateTime
    )
}
