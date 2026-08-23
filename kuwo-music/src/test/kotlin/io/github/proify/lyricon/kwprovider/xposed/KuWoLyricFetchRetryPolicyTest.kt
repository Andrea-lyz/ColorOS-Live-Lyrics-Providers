/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kwprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuWoLyricFetchRetryPolicyTest {
    private fun newPolicy() = KuWoLyricFetchRetryPolicy(
        maxAttempts = 3,
        backoffDelayMs = listOf(2000L, 5000L, 10000L)
    )

    private val currentCall = KuWoLyricFetchRetryPolicy.FetchCall(
        rid = "1",
        trackKey = "comeback|carly"
    )

    @Test
    fun failedFetchForCurrentTrackSchedulesBackoffThenExhausts() {
        val policy = newPolicy()
        policy.noteTrackChanged("comeback|carly", "1")
        assertEquals(2000L, policy.noteFetchFailed(currentCall))
        assertEquals(5000L, policy.noteFetchFailed(currentCall))
        assertEquals(10000L, policy.noteFetchFailed(currentCall))
        assertNull(policy.noteFetchFailed(currentCall))
    }

    @Test
    fun failedFetchForOtherTrackIsIgnored() {
        val policy = newPolicy()
        policy.noteTrackChanged("comeback|carly", "1")
        assertNull(
            policy.noteFetchFailed(
                KuWoLyricFetchRetryPolicy.FetchCall(rid = "2", trackKey = "other|artist")
            )
        )
        assertNull(policy.takeRetryCall())
    }

    @Test
    fun trackChangeAdoptsPriorFailedFetch() {
        val policy = newPolicy()
        assertNull(policy.noteFetchFailed(currentCall))
        assertEquals(2000L, policy.noteTrackChanged("comeback|carly", "1"))
        assertEquals(currentCall, policy.takeRetryCall())
    }

    @Test
    fun emittedLyricsCancelRetries() {
        val policy = newPolicy()
        policy.noteTrackChanged("comeback|carly", "1")
        assertEquals(2000L, policy.noteFetchFailed(currentCall))
        policy.noteLyricsEmitted("1", "comeback|carly")
        assertNull(policy.noteFetchFailed(currentCall))
        assertNull(policy.takeRetryCall())
    }

    @Test
    fun fetchSuccessClearsPendingRetry() {
        val policy = newPolicy()
        policy.noteTrackChanged("comeback|carly", "1")
        assertEquals(2000L, policy.noteFetchFailed(currentCall))
        policy.noteFetchSucceeded(currentCall)
        assertNull(policy.takeRetryCall())
    }
}
