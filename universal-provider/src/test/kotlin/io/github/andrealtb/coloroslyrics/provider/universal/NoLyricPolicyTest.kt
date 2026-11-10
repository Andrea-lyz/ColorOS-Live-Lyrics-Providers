package io.github.andrealtb.coloroslyrics.provider.universal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoLyricPolicyTest {
    @Test
    fun usableLyricPublishes() {
        assertEquals(
            NoLyricPolicy.Outcome.PUBLISH_LYRICS,
            NoLyricPolicy.decide(hasUsableLyric = true, instrumental = false, transientFailure = false)
        )
    }

    @Test
    fun creditsOnlyIsAutoNegative() {
        assertEquals(
            NoLyricPolicy.Outcome.AUTO_NEGATIVE,
            NoLyricPolicy.decide(hasUsableLyric = true, instrumental = true, transientFailure = false)
        )
        assertEquals(NoLyricPolicy.REASON_CREDITS, NoLyricPolicy.autoReason(instrumental = true))
    }

    @Test
    fun confirmedMissIsNotANegativeResult() {
        assertEquals(
            NoLyricPolicy.Outcome.AUTO_MISS,
            NoLyricPolicy.decide(hasUsableLyric = false, instrumental = false, transientFailure = false)
        )
        assertEquals(NoLyricPolicy.REASON_MISS, NoLyricPolicy.autoReason(instrumental = false))
    }

    @Test
    fun transientFailureNeverCaches() {
        assertEquals(
            NoLyricPolicy.Outcome.TRANSIENT_RETRY,
            NoLyricPolicy.decide(hasUsableLyric = false, instrumental = false, transientFailure = true)
        )
    }

    @Test
    fun payloadFlags() {
        val auto = "{\"noLyric\":true,\"noLyricReason\":\"confirmedMiss\"}"
        val user = "{\"noLyric\":true,\"noLyricReason\":\"userConfirmed\"}"
        val lyric = "{\"noLyric\":false,\"lyric\":\"[00:01.000]hi\"}"
        assertTrue(NoLyricPolicy.isNoLyric(auto))
        // A legacy automatic miss is retryable, so it must not suppress the network.
        assertFalse(NoLyricPolicy.shouldSkipNetwork(auto))
        assertTrue(NoLyricPolicy.isRetryableMiss(auto))
        assertFalse(NoLyricPolicy.isUserConfirmed(auto))
        assertTrue(NoLyricPolicy.shouldSkipNetwork(user))
        assertTrue(NoLyricPolicy.isUserConfirmed(user))
        assertFalse(NoLyricPolicy.isRetryableMiss(user))
        assertFalse(NoLyricPolicy.isNoLyric(lyric))
        assertFalse(NoLyricPolicy.shouldSkipNetwork(lyric))
    }

    @Test
    fun missPayloadIsNotANegativeResult() {
        val miss = NoLyricPolicy.missPayloadJson("Song", "Artist")
        assertFalse(NoLyricPolicy.isNoLyric(miss))
        assertTrue(NoLyricPolicy.isRetryableMiss(miss))
        assertFalse(NoLyricPolicy.shouldSkipNetwork(miss))
    }
}
