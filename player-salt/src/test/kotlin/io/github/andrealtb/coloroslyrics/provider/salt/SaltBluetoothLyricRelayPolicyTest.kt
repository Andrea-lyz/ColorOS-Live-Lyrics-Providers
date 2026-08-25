/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaltBluetoothLyricRelayPolicyTest {

    @Test
    fun parseRelayIdentityMatchesBridgeFixtures() {
        val first = SaltBluetoothLyricRelayPolicy.parseRelayIdentity(
            "William Black/Fairlane - Broken"
        )
        assertNotNull(first)
        assertEquals("William Black/Fairlane", first.artist)
        assertEquals("Broken", first.title)

        val second = SaltBluetoothLyricRelayPolicy.parseRelayIdentity(
            "Porter Robinson - Kitsune Maison Freestyle - Live"
        )
        assertNotNull(second)
        assertEquals("Porter Robinson", second.artist)
        assertEquals("Kitsune Maison Freestyle - Live", second.title)

        assertEquals(
            "All I Ask",
            SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Adele — All I Ask")?.title
        )
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Adele"))
    }

    @Test
    fun displayIdentityWinsOverDynamicRelayTitle() {
        val resolved = SaltBluetoothLyricRelayPolicy.resolveFields(
            mediaId = "song-1",
            title = "Current lyric line",
            artist = "William Black/Fairlane - Broken",
            displayTitle = "Broken",
            displaySubtitle = "William Black/Fairlane",
            album = "Album",
            durationMs = 180000L
        )

        assertNotNull(resolved)
        assertTrue(resolved.relay)
        assertEquals("display", resolved.source)
        assertEquals("song-1", resolved.track.id)
        assertEquals("Broken", resolved.track.title)
        assertEquals("William Black/Fairlane", resolved.track.artist)
    }

    @Test
    fun compositeArtistRestoresIdentityWithoutDisplayFields() {
        val resolved = SaltBluetoothLyricRelayPolicy.resolveFields(
            mediaId = null,
            title = "Another lyric line",
            artist = "William Black/Fairlane - Broken",
            displayTitle = null,
            displaySubtitle = null,
            album = "Album",
            durationMs = 180000L
        )

        assertNotNull(resolved)
        assertTrue(resolved.relay)
        assertEquals("relay-artist", resolved.source)
        assertEquals("Broken", resolved.track.title)
        assertEquals("William Black/Fairlane", resolved.track.artist)
    }

    @Test
    fun changingLyricLinesDoesNotChangeResolvedTrack() {
        val first = resolveRelay("First line")
        val second = resolveRelay("Second line")

        assertTrue(TrackIdentityPolicy.isSameTrack(first.track, second.track))
        assertEquals(first.track.buildStableKey(), second.track.buildStableKey())
    }

    @Test
    fun realSongChangeStillChangesResolvedTrack() {
        val first = resolveRelay("First line")
        val second = SaltBluetoothLyricRelayPolicy.resolveFields(
            mediaId = "song-2",
            title = "New lyric",
            artist = "Adele - All I Ask",
            displayTitle = "All I Ask",
            displaySubtitle = "Adele",
            album = "25",
            durationMs = 240000L
        )!!

        assertFalse(TrackIdentityPolicy.isSameTrack(first.track, second.track))
    }

    @Test
    fun ordinaryMetadataRemainsOrdinary() {
        val resolved = SaltBluetoothLyricRelayPolicy.resolveFields(
            mediaId = "song-1",
            title = "Broken",
            artist = "William Black/Fairlane",
            displayTitle = null,
            displaySubtitle = null,
            album = "Album",
            durationMs = 180000L
        )
        assertNotNull(resolved)
        assertFalse(resolved.relay)
        assertEquals("standard", resolved.source)
    }

    private fun resolveRelay(line: String) =
        SaltBluetoothLyricRelayPolicy.resolveFields(
            mediaId = "song-1",
            title = line,
            artist = "William Black/Fairlane - Broken",
            displayTitle = "Broken",
            displaySubtitle = "William Black/Fairlane",
            album = "Album",
            durationMs = 180000L
        )!!
}
