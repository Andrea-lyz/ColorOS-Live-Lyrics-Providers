/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.proify.lyricon.yrckit.download.YrcDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Async eAPI lifecycle used only by the NetEase 9.0.40 :play profile. */
class NeteaseConstructedLyricSession(
    private val processName: String,
    private val coordinator: NeteaseLyricSessionCoordinator
) {
    private val requestLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null
    private var requestKey = ""

    fun request(track: TrackIdentity, generation: Long) {
        val request = NeteaseConstructedLyricPolicy.request(track, generation) ?: return
        synchronized(requestLock) {
            if (requestKey == request.key) return
            requestKey = request.key
            job?.cancel()
            job = scope.launch {
                fetch(request)
            }
        }
    }

    private suspend fun fetch(request: NeteaseConstructedLyricPolicy.Request) {
        NeteaseDiagnostics.info(
            area = "lyric",
            event = "CONSTRUCTED_FETCH_REQUESTED",
            process = processName,
            generation = request.generation,
            session = request.track.buildStableKey()
        )
        var lastFailure: Throwable? = null
        for (attempt in 1..FETCH_ATTEMPTS) {
            try {
                val response = YrcDownloader.fetchLyric(request.musicId)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (response.code != 0 && response.code != 200) {
                    error("NetEase lyric response code=${response.code}")
                }
                val current = coordinator.currentSnapshot()
                if (!NeteaseConstructedLyricPolicy.isCurrent(
                        request,
                        current.track,
                        current.generation
                    )
                ) {
                    NeteaseDiagnostics.info(
                        area = "lyric",
                        event = "CONSTRUCTED_FETCH_STALE",
                        process = processName,
                        generation = request.generation,
                        session = request.track.id,
                        reason = "generation-mismatch",
                        message = "currentGeneration=${current.generation}"
                    )
                    return
                }
                val snapshot = NeteaseConstructedLyricPolicy.snapshot(request, response)
                val lines = NeteaseLyricDecoder.decode(snapshot)
                if (lines.isEmpty()) {
                    NeteaseDiagnostics.info(
                        area = "lyric",
                        event = "CONSTRUCTED_DECODE_EMPTY",
                        process = processName,
                        generation = request.generation,
                        session = request.track.id,
                        reason = if (response.pureMusic) "pure-music" else "no-lines",
                        message = coordinator.describeSnapshot(snapshot)
                    )
                    return
                }
                NeteaseDiagnostics.info(
                    area = "lyric",
                    event = "CONSTRUCTED_FETCH_HIT",
                    process = processName,
                    generation = request.generation,
                    session = request.track.id,
                    message = "attempt=$attempt lines=${lines.size} " +
                        "translated=${lines.count { !it.secondary.isNullOrBlank() }}"
                )
                coordinator.emitConstructed(
                    lines = lines,
                    track = request.track,
                    captureOrigin = "eapi"
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt < FETCH_ATTEMPTS) {
                    NeteaseDiagnostics.info(
                        area = "lyric",
                        event = "CONSTRUCTED_FETCH_RETRY",
                        process = processName,
                        generation = request.generation,
                        session = request.track.id,
                        reason = failure.javaClass.simpleName,
                        message = "attempt=$attempt ${failure.message.orEmpty()}"
                    )
                    delay(FETCH_RETRY_DELAY_MS * attempt)
                }
            }
        }
        synchronized(requestLock) {
            if (requestKey == request.key) requestKey = ""
        }
        NeteaseDiagnostics.error(
            area = "lyric",
            event = "CONSTRUCTED_FETCH_FAILED",
            process = processName,
            reason = lastFailure?.javaClass?.simpleName,
            message = lastFailure?.message.orEmpty(),
            throwable = lastFailure
        )
    }

    private companion object {
        const val FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 350L
    }
}
