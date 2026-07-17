/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.android

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackPositionSnapshot
import io.github.proify.extensions.bridge.PlaybackTrackToken
import java.lang.ref.WeakReference

/**
 * Optional helper for providers whose lyrics arrive after MediaSession events.
 *
 * A song commit is accepted only for the exact track generation and MediaSession that produced
 * the metadata. When a state is available, the ordering is always song -> position -> state ->
 * lyric event -> playback event.
 */
class PlaybackStateCommitter {
    private var currentTrack: PlaybackTrackToken? = null
    private var authoritativeSession = WeakReference<MediaSession>(null)
    private var latestState: PlaybackState? = null
    private var latestSnapshot: PlaybackPositionSnapshot? = null
    private var lastObservedSessionIdentity = 0
    private var lastObservedState: PlaybackState? = null
    private var lastObservedAtElapsedMillis = 0L

    @Synchronized
    fun bindTrack(
        mediaId: String,
        generation: Long,
        session: MediaSession,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
        reusePreBindState: Boolean = true
    ): PlaybackTrackToken? {
        if (mediaId.isBlank() || generation <= 0L) return null
        val sessionIdentity = System.identityHashCode(session)
        val track = PlaybackTrackToken(mediaId, generation, sessionIdentity)
        currentTrack = track
        authoritativeSession = WeakReference(session)
        latestState = null
        latestSnapshot = null

        val observedState = lastObservedState
        if (reusePreBindState &&
            lastObservedSessionIdentity == sessionIdentity &&
            observedState != null &&
            nowElapsedMillis >= lastObservedAtElapsedMillis &&
            nowElapsedMillis - lastObservedAtElapsedMillis <= PRE_METADATA_STATE_MAX_AGE_MS
        ) {
            rememberState(track, observedState, lastObservedAtElapsedMillis)
        }
        return track
    }

    @Synchronized
    fun observePlaybackState(
        session: MediaSession,
        state: PlaybackState,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime()
    ): PlaybackTrackToken? {
        val sessionIdentity = System.identityHashCode(session)
        lastObservedSessionIdentity = sessionIdentity
        lastObservedState = state
        lastObservedAtElapsedMillis = nowElapsedMillis

        val track = currentTrack
        if (!PlaybackCommitPolicy.acceptsPlayback(track, sessionIdentity)) return null
        rememberState(track!!, state, nowElapsedMillis)
        return track
    }

    @Synchronized
    fun currentTrack(): PlaybackTrackToken? = currentTrack

    fun commit(
        requestedTrack: PlaybackTrackToken,
        responseMediaId: String?,
        duration: Long,
        setSong: () -> Unit,
        setPosition: (Long) -> Unit,
        replayPlaybackState: (PlaybackState) -> Unit,
        publishLyricReady: () -> Unit,
        publishPlaybackState: (PlaybackState) -> Unit,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
        refreshStateFromSession: Boolean = true
    ): PlaybackCommitResult {
        val prepared = prepareCommit(
            requestedTrack = requestedTrack,
            responseMediaId = responseMediaId,
            duration = duration,
            nowElapsedMillis = nowElapsedMillis,
            refreshStateFromSession = refreshStateFromSession
        ) ?: return PlaybackCommitResult.Rejected

        return runCatching {
            setSong()
            prepared.position?.let(setPosition)
            prepared.state?.let(replayPlaybackState)
            publishLyricReady()
            prepared.state?.let(publishPlaybackState)
            PlaybackCommitResult.Committed(prepared.position, prepared.state)
        }.getOrElse { PlaybackCommitResult.Failed(it) }
    }

    private fun prepareCommit(
        requestedTrack: PlaybackTrackToken,
        responseMediaId: String?,
        duration: Long,
        nowElapsedMillis: Long,
        refreshStateFromSession: Boolean
    ): PreparedCommit? {
        val session: MediaSession
        synchronized(this) {
            if (!PlaybackCommitPolicy.acceptsResult(currentTrack, requestedTrack, responseMediaId)) {
                return null
            }
            session = authoritativeSession.get() ?: return null
        }

        val queriedState = if (refreshStateFromSession) {
            runCatching { session.controller.playbackState }.getOrNull()
        } else {
            null
        }
        synchronized(this) {
            if (!PlaybackCommitPolicy.acceptsResult(currentTrack, requestedTrack, responseMediaId) ||
                System.identityHashCode(session) != requestedTrack.sessionIdentity
            ) {
                return null
            }
            if (queriedState != null) {
                rememberState(requestedTrack, queriedState, nowElapsedMillis)
            }
            val state = latestState
            val snapshot = latestSnapshot?.takeIf { it.track == requestedTrack }
            val position = snapshot?.let {
                PlaybackCommitPolicy.projectPosition(it, nowElapsedMillis, duration)
            }
            return PreparedCommit(position, state)
        }
    }

    private fun rememberState(
        track: PlaybackTrackToken,
        state: PlaybackState,
        observedAtElapsedMillis: Long
    ) {
        latestState = state
        latestSnapshot = PlaybackPositionSnapshot(
            position = state.position,
            speed = state.playbackSpeed,
            lastPositionUpdateTime = state.lastPositionUpdateTime,
            moving = state.state == PlaybackState.STATE_PLAYING ||
                state.state == PlaybackState.STATE_FAST_FORWARDING ||
                state.state == PlaybackState.STATE_REWINDING,
            track = track,
            observedAtElapsedMillis = observedAtElapsedMillis
        )
    }

    private data class PreparedCommit(
        val position: Long?,
        val state: PlaybackState?
    )

    sealed interface PlaybackCommitResult {
        data object Rejected : PlaybackCommitResult
        data class Committed(val position: Long?, val state: PlaybackState?) : PlaybackCommitResult
        data class Failed(val throwable: Throwable) : PlaybackCommitResult
    }

    private companion object {
        const val PRE_METADATA_STATE_MAX_AGE_MS = 30_000L
    }
}
