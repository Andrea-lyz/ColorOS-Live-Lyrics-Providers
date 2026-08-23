/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kwprovider.xposed

import java.lang.reflect.Method

/**
 * Bounded retry bookkeeping for KuWo's own lyric fetch.
 *
 * KuWo fetches lyrics once per track start and again on pause/resume. A single
 * failed network attempt therefore leaves the lockscreen on the big-cover
 * state until the user manually pauses. This policy remembers the failed fetch
 * call and lets the hooker re-invoke it with the original arguments a small
 * number of times while the current track still has no lyrics.
 */
internal class KuWoLyricFetchRetryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffDelayMs: List<Long> = DEFAULT_BACKOFF_DELAY_MS
) {
    data class FetchCall(
        val rid: String?,
        val trackKey: String?
    )

    class FetchInvoke(
        val method: Method,
        val instance: Any?,
        val args: Array<Any?>,
        val call: FetchCall
    )

    private val lock = Any()
    private var lastFailedCall: FetchCall? = null
    private var failedCall: FetchCall? = null
    private var attempts = 0
    private var currentTrackKey: String? = null
    private var currentMediaId: String? = null
    private var currentTrackHasLyrics = false

    /** Returns the delay for the next retry, or null when nothing is scheduled. */
    fun noteFetchFailed(call: FetchCall): Long? {
        synchronized(lock) {
            lastFailedCall = call
            if (currentTrackHasLyrics) return null
            if (!matchesCurrent(call)) return null
            if (attempts >= maxAttempts) return null
            failedCall = call
            val delay = backoffDelayMs[attempts]
            attempts += 1
            return delay
        }
    }

    fun noteFetchSucceeded(call: FetchCall) {
        synchronized(lock) {
            if (matchesCurrent(call)) {
                lastFailedCall = null
                failedCall = null
                attempts = 0
            }
        }
    }

    fun noteLyricsEmitted(rid: String?, trackKey: String?) {
        synchronized(lock) {
            if (matchesCurrent(FetchCall(rid, trackKey))) {
                currentTrackHasLyrics = true
                lastFailedCall = null
                failedCall = null
                attempts = 0
            }
        }
    }

    /**
     * Adopts a failed fetch that completed before the track identity arrived,
     * so a network failure racing the metadata change still gets retried.
     */
    fun noteTrackChanged(trackKey: String?, mediaId: String?): Long? {
        synchronized(lock) {
            currentTrackKey = trackKey
            currentMediaId = mediaId
            currentTrackHasLyrics = false
            attempts = 0
            failedCall = null
            val prior = lastFailedCall ?: return null
            if (!matchesCurrent(prior)) return null
            failedCall = prior
            attempts = 1
            return backoffDelayMs[0]
        }
    }

    /** The call a scheduled retry should re-invoke, if it still matches the track. */
    fun takeRetryCall(): FetchCall? {
        synchronized(lock) {
            val call = failedCall ?: return null
            if (currentTrackHasLyrics || !matchesCurrent(call)) {
                failedCall = null
                return null
            }
            return call
        }
    }

    fun pendingAttempts(): Int = synchronized(lock) { attempts }

    private fun matchesCurrent(call: FetchCall?): Boolean {
        if (call == null) return false
        val mediaId = currentMediaId
        if (!mediaId.isNullOrBlank() && !call.rid.isNullOrBlank()) {
            return call.rid == mediaId
        }
        val trackKey = currentTrackKey
        if (!trackKey.isNullOrBlank() && !call.trackKey.isNullOrBlank()) {
            return call.trackKey == trackKey
        }
        return false
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        val DEFAULT_BACKOFF_DELAY_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L)
    }
}
