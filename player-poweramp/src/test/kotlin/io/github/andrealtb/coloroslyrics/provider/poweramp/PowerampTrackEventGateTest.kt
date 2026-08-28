/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerampTrackEventGateTest {
    @Test
    fun rejectsTheSameIntentObservedByHookAndReceiver() {
        val gate = PowerampTrackEventGate()

        assertTrue(gate.shouldHandle(1_000L, "track-1"))
        assertFalse(gate.shouldHandle(1_000L, "track-1"))
    }

    @Test
    fun acceptsDifferentTracksEvenWhenTimestampsCollide() {
        val gate = PowerampTrackEventGate()

        assertTrue(gate.shouldHandle(1_000L, "track-1"))
        assertTrue(gate.shouldHandle(1_000L, "track-2"))
    }

    @Test
    fun eventsWithoutTimestampRemainCompatible() {
        val gate = PowerampTrackEventGate()

        assertTrue(gate.shouldHandle(Long.MIN_VALUE, "track-1"))
        assertTrue(gate.shouldHandle(Long.MIN_VALUE, "track-1"))
    }
}
