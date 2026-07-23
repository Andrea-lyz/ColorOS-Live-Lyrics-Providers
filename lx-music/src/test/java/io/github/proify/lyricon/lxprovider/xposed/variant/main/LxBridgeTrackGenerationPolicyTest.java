/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LxBridgeTrackGenerationPolicyTest {
    @Test
    public void usesElapsedRealtimeAfterThePlayerProcessRestarts() {
        long generation = LxBridgeTrackGenerationPolicy.next(0L, 1_032_456L);

        assertEquals(1_032_456L, generation);
    }

    @Test
    public void staysStrictlyIncreasingForTrackChangesInTheSameClockTick() {
        long generation = LxBridgeTrackGenerationPolicy.next(1_032_456L, 1_032_456L);

        assertEquals(1_032_457L, generation);
    }

    @Test
    public void neverPublishesZeroOrANegativeGeneration() {
        assertEquals(1L, LxBridgeTrackGenerationPolicy.next(0L, 0L));
        assertEquals(1L, LxBridgeTrackGenerationPolicy.next(-5L, -10L));
    }

    @Test
    public void doesNotWrapTheMaximumGenerationBackToAStaleValue() {
        long generation = LxBridgeTrackGenerationPolicy.next(Long.MAX_VALUE, 1_032_456L);

        assertEquals(Long.MAX_VALUE, generation);
        assertTrue(generation > 0L);
    }
}
