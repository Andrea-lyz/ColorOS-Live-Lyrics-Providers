/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PowerampTrackIdentityTest {
    @Test
    fun mediaIdUsesTrailingSegmentAsStableId() {
        assertEquals("42", PowerampTrackIdentity.normalizeId("content://tracks/42"))
        assertEquals("42", PowerampTrackIdentity.normalizeId("42"))
        assertNull(PowerampTrackIdentity.normalizeId("-1"))
        assertNull(PowerampTrackIdentity.normalizeId("  "))
    }

    @Test
    fun broadcastAndMetadataShareTheSameNumericId() {
        val fromBroadcast = PowerampTrackIdentity.fromBroadcast(
            id = "42",
            title = " Song ",
            artist = " Artist ",
            album = "Album",
            durationMs = 180_000L
        )
        val fromMediaId = TrackIdentity(
            id = PowerampTrackIdentity.normalizeId("android/media/42"),
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L
        )
        assertEquals(fromBroadcast?.id, fromMediaId.id)
        assertEquals("Song", fromBroadcast?.title)
        assertEquals("Artist", fromBroadcast?.artist)
    }

    @Test
    fun integerDurationFromTrackChangedSurvives() {
        assertEquals(295_000L, PowerampTrackIdentity.longValue(295_000, 0L))
        assertEquals(295_000L, PowerampTrackIdentity.longValue(295_000L, 0L))
        assertEquals(295_000L, PowerampTrackIdentity.longValue("295000", 0L))
        assertEquals(0L, PowerampTrackIdentity.longValue("not-a-number", 0L))
        assertEquals(-1L, PowerampTrackIdentity.longValue(null, -1L))
    }

    @Test
    fun blankBroadcastWithoutTitleIsRejected() {
        assertNull(
            PowerampTrackIdentity.fromBroadcast(
                id = null,
                title = null,
                artist = null,
                album = null,
                durationMs = 0L
            )
        )
        assertNotNull(
            PowerampTrackIdentity.fromBroadcast(
                id = "1",
                title = null,
                artist = null,
                album = null,
                durationMs = 0L
            )
        )
    }
}
