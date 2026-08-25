/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaltBluetoothLyricRelayPolicyTest {

    @Test
    fun parseRelayIdentityMatchesThreeBridgeFixtures() {
        // Fixture 1: William Black/Fairlane - Broken
        val fixture1 = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("William Black/Fairlane - Broken")
        assertNotNull(fixture1)
        assertEquals("William Black/Fairlane", fixture1.artist)
        assertEquals("Broken", fixture1.title)

        // Fixture 2: Porter Robinson - Kitsune Maison Freestyle - Live (preserves extra separators in title)
        val fixture2 = SaltBluetoothLyricRelayPolicy.parseRelayIdentity(
            "Porter Robinson - Kitsune Maison Freestyle - Live"
        )
        assertNotNull(fixture2)
        assertEquals("Porter Robinson", fixture2.artist)
        assertEquals("Kitsune Maison Freestyle - Live", fixture2.title)

        // Fixture 3: Adele - All I Ask
        val fixture3 = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Adele - All I Ask")
        assertNotNull(fixture3)
        assertEquals("Adele", fixture3.artist)
        assertEquals("All I Ask", fixture3.title)
    }

    @Test
    fun parseRelayIdentityHandlesAllSupportedSeparators() {
        val hyphen = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Artist - Title")
        assertNotNull(hyphen)
        assertEquals("Artist", hyphen.artist)
        assertEquals("Title", hyphen.title)

        val enDash = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Artist – Title")
        assertNotNull(enDash)
        assertEquals("Artist", enDash.artist)
        assertEquals("Title", enDash.title)

        val emDash = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Artist — Title")
        assertNotNull(emDash)
        assertEquals("Artist", emDash.artist)
        assertEquals("Title", emDash.title)
    }

    @Test
    fun parseRelayIdentityRejectsNonRelayAndInvalidInputs() {
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity(null))
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity(""))
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity("   "))
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Taylor Swift"))
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity("SingleArtistWithoutSeparator"))
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity(" - Broken"))
        assertNull(SaltBluetoothLyricRelayPolicy.parseRelayIdentity("Artist - "))
    }

    @Test
    fun matchesStableChecksTrackIdentityAndDuration() {
        val stable = TrackIdentity(
            id = "1",
            title = "Broken",
            artist = "William Black/Fairlane",
            durationMs = 180000L
        )

        assertTrue(SaltBluetoothLyricRelayPolicy.matchesStable(stable, "Broken", "William Black/Fairlane", 180000L))
        assertTrue(SaltBluetoothLyricRelayPolicy.matchesStable(stable, "Broken", "William Black/Fairlane", 0L))
        assertTrue(
            SaltBluetoothLyricRelayPolicy.matchesStable(
                stable.copy(durationMs = 0L),
                "Broken",
                "William Black/Fairlane",
                180000L
            )
        )

        assertFalse(SaltBluetoothLyricRelayPolicy.matchesStable(stable, "Other Title", "William Black/Fairlane", 180000L))
        assertFalse(SaltBluetoothLyricRelayPolicy.matchesStable(stable, "Broken", "Other Artist", 180000L))
        assertFalse(SaltBluetoothLyricRelayPolicy.matchesStable(stable, "Broken", "William Black/Fairlane", 240000L))
        assertFalse(SaltBluetoothLyricRelayPolicy.matchesStable(null, "Broken", "William Black/Fairlane", 180000L))
    }

    @Test
    fun firstRelayRecoversOnlyFromMatchingPendingTrack() {
        val relay = SaltBluetoothLyricRelayPolicy.parseRelayIdentity(
            "William Black/Fairlane - Broken"
        )!!
        val matching = TrackIdentity(
            id = "song-1",
            title = "Broken",
            artist = "William Black/Fairlane",
            durationMs = 180000L
        )

        assertEquals(
            matching,
            SaltBluetoothLyricRelayPolicy.recoverTrackFromPending(
                matching,
                relay,
                180000L
            )
        )
        assertNull(
            SaltBluetoothLyricRelayPolicy.recoverTrackFromPending(
                matching.copy(id = "song-2", title = "Other"),
                relay,
                180000L
            )
        )
        assertNull(
            SaltBluetoothLyricRelayPolicy.recoverTrackFromPending(
                null,
                relay,
                180000L
            )
        )
    }
}
