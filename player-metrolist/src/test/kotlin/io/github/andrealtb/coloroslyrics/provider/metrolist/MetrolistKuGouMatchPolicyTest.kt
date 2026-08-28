/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetrolistKuGouMatchPolicyTest {
    @Test
    fun platformMillisecondsBecomeHostSeconds() {
        assertEquals(0, MetrolistKuGouMatchPolicy.queryDurationSeconds(0L))
        assertEquals(0, MetrolistKuGouMatchPolicy.queryDurationSeconds(-1L))
        assertEquals(231, MetrolistKuGouMatchPolicy.queryDurationSeconds(231_000L))
    }

    @Test
    fun unknownDurationIsAWildcard() {
        assertTrue(MetrolistKuGouMatchPolicy.matchesSongDuration(231, 0))
        assertTrue(MetrolistKuGouMatchPolicy.matchesSongDuration(231, -1))
        assertTrue(MetrolistKuGouMatchPolicy.matchesSongDuration(180, 0))
    }

    @Test
    fun leftoverPreviousTrackDurationOutsideToleranceIsRejected() {
        assertTrue(MetrolistKuGouMatchPolicy.matchesSongDuration(231, 238))
        assertFalse(MetrolistKuGouMatchPolicy.matchesSongDuration(231, 250))
        assertFalse(MetrolistKuGouMatchPolicy.matchesSongDuration(180, 238))
    }

    @Test
    fun hostUnknownSecondsMapToZeroQueryDuration() {
        assertEquals(0L, MetrolistHostMetadata.durationMsFromHostSeconds(null))
        assertEquals(0L, MetrolistHostMetadata.durationMsFromHostSeconds(-1))
        assertEquals(0L, MetrolistHostMetadata.durationMsFromHostSeconds(0))
        assertEquals(231_000L, MetrolistHostMetadata.durationMsFromHostSeconds(231))
        assertEquals(0, MetrolistKuGouMatchPolicy.queryDurationSeconds(
            MetrolistHostMetadata.durationMsFromHostSeconds(-1)
        ))
    }
}
