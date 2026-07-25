/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEventGatesTest {
    @Test
    fun payloadGateSuppressesOnlyRecentMatchingPayload() {
        val gate = BridgePayloadGate(1_000L)

        assertTrue(gate.shouldSend("track:payload-a", 100L))
        assertFalse(gate.shouldSend("track:payload-a", 500L))
        assertTrue(gate.shouldSend("track:payload-b", 600L))
        // payload-a is still inside the 1000ms window: it must keep being
        // suppressed even after payload-b was admitted.
        assertFalse(gate.shouldSend("track:payload-a", 700L))
        // Different key matches no recent entry — pass through.
        assertTrue(gate.shouldSend("track:payload-c", 800L))
        // After the window, payload-a may be sent again.
        assertTrue(gate.shouldSend("track:payload-a", 1_701L))
    }

    @Test
    fun payloadGateAllowsRetryAfterFailure() {
        val gate = BridgePayloadGate()

        assertTrue(gate.shouldSend("payload", 100L))
        gate.forget("payload")
        assertTrue(gate.shouldSend("payload", 101L))
    }

    @Test
    fun payloadGateKeepsIndependentEntriesPerSource() {
        // Two providers (Apple Music + Spotify) firing in the same 30s window
        // must not push each other out of the gate.
        val gate = BridgePayloadGate(30_000L)

        assertTrue(gate.shouldSend("apple:1:reqA", 1_000L))
        assertTrue(gate.shouldSend("spotify:1:reqB", 1_500L))
        // Both sources are independently active — duplicates within 30s.
        assertFalse(gate.shouldSend("apple:1:reqA", 2_000L))
        assertFalse(gate.shouldSend("spotify:1:reqB", 2_500L))
    }

    @Test
    fun payloadGateForgetIgnoresKeyDrift() {
        // Bug fix: previously forget only matched the *last* key, so if a
        // second source slipped in between shouldSend and forget, the original
        // forget would be a no-op. The gate now keeps a bounded list of recent
        // entries and forget reaches any of them.
        val gate = BridgePayloadGate(30_000L)

        assertTrue(gate.shouldSend("apple:1:reqA", 1_000L))
        assertTrue(gate.shouldSend("spotify:1:reqB", 1_500L))
        // Apple Music's send failed and asks the gate to forget its key.
        gate.forget("apple:1:reqA")
        // The retry should now pass even though the gate last saw Spotify.
        assertTrue(gate.shouldSend("apple:1:reqA", 1_600L))
        // Spotify is still suppressed.
        assertFalse(gate.shouldSend("spotify:1:reqB", 1_700L))
    }

    @Test
    fun payloadGateForgetIsNoopForUnknownKey() {
        val gate = BridgePayloadGate(30_000L)

        assertTrue(gate.shouldSend("apple:1:reqA", 1_000L))
        gate.forget("unknown")
        assertFalse(gate.shouldSend("apple:1:reqA", 1_100L))
    }

    @Test
    fun payloadGateForgetIgnoresBlankKey() {
        val gate = BridgePayloadGate(30_000L)

        assertTrue(gate.shouldSend("apple:1:reqA", 1_000L))
        gate.forget("")
        gate.forget("   ")
        assertFalse(gate.shouldSend("apple:1:reqA", 1_100L))
    }

    @Test
    fun payloadGateResetClearsAllEntries() {
        val gate = BridgePayloadGate(30_000L)

        assertTrue(gate.shouldSend("apple:1:reqA", 1_000L))
        assertTrue(gate.shouldSend("spotify:1:reqB", 1_500L))
        gate.reset()
        assertTrue(gate.shouldSend("apple:1:reqA", 2_000L))
        assertTrue(gate.shouldSend("spotify:1:reqB", 2_100L))
    }

    @Test
    fun payloadGateEvictsExpiredEntriesOnNextCall() {
        val gate = BridgePayloadGate(1_000L)

        assertTrue(gate.shouldSend("apple:1:reqA", 100L))
        assertTrue(gate.shouldSend("spotify:1:reqB", 200L))
        // After the window the entries should fall out without an explicit
        // reset, so the same keys can be sent again.
        assertTrue(gate.shouldSend("apple:1:reqA", 1_300L))
        assertTrue(gate.shouldSend("spotify:1:reqB", 1_400L))
    }

    @Test
    fun payloadGateCapsRecentEntries() {
        // maxEntries is a hard upper bound. Once the queue reaches
        // [k1, k2, k3] a fourth insert must evict the oldest entry (k1)
        // even though all three are still inside the duplicate window.
        val gate = BridgePayloadGate(duplicateWindowMs = 10_000L, maxEntries = 3)

        assertTrue(gate.shouldSend("k1", 100L))
        assertTrue(gate.shouldSend("k2", 200L))
        assertTrue(gate.shouldSend("k3", 300L))
        // Queue is [k1, k2, k3], size=3 == maxEntries. Inserting k4 evicts k1.
        assertTrue(gate.shouldSend("k4", 400L))
        // k1 is no longer in the queue, so it can pass through again even
        // though the 10s window would otherwise still suppress it.
        assertTrue(gate.shouldSend("k1", 500L))
    }

    @Test
    fun payloadGateBlankKeySkipsGate() {
        val gate = BridgePayloadGate(30_000L)

        assertTrue(gate.shouldSend("", 100L))
        assertTrue(gate.shouldSend("   ", 100L))
    }

    @Test
    fun playbackGateSuppressesEquivalentMovingUpdates() {
        val gate = BridgePlaybackStateGate(positionToleranceMs = 400L, heartbeatMs = 10_000L)

        assertTrue(gate.shouldSend(3, 1_000L, 1f, 1_000L, true, 7L, 1_000L))
        assertFalse(gate.shouldSend(3, 1_500L, 1f, 1_500L, true, 7L, 1_500L))
        assertTrue(gate.shouldSend(3, 5_000L, 1f, 1_600L, true, 7L, 1_600L))
    }

    @Test
    fun playbackGateKeepsTransitionsSeeksGenerationsAndHeartbeat() {
        val gate = BridgePlaybackStateGate(positionToleranceMs = 400L, heartbeatMs = 10_000L)

        assertTrue(gate.shouldSend(3, 1_000L, 1f, 1_000L, true, 1L, 1_000L))
        assertTrue(gate.shouldSend(2, 1_100L, 0f, 1_100L, false, 1L, 1_100L))
        assertTrue(gate.shouldSend(2, 8_000L, 0f, 1_200L, false, 1L, 1_200L))
        assertTrue(gate.shouldSend(2, 8_000L, 0f, 1_300L, false, 2L, 1_300L))
        assertFalse(gate.shouldSend(2, 8_000L, 0f, 1_400L, false, 2L, 10_000L))
        assertTrue(gate.shouldSend(2, 8_000L, 0f, 1_400L, false, 2L, 11_300L))
    }

    @Test
    fun playbackGateCanForceCorrectiveSnapshot() {
        val gate = BridgePlaybackStateGate(positionToleranceMs = 400L, heartbeatMs = 10_000L)

        assertTrue(gate.shouldSend(3, 1_000L, 1f, 1_000L, true, 7L, 1_000L))
        assertFalse(gate.shouldSend(3, 2_000L, 1f, 2_000L, true, 7L, 2_000L))
        assertTrue(gate.shouldSend(3, 2_000L, 1f, 2_000L, true, 7L, 2_000L, force = true))
    }
}
