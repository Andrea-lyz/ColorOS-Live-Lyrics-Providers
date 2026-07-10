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
        assertTrue(gate.shouldSend("track:payload-a", 700L))
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
