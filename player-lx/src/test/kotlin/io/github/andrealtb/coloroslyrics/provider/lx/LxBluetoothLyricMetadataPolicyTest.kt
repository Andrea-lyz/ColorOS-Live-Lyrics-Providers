/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LxBluetoothLyricMetadataPolicyTest {

    @Test
    fun recognizesMainLxBluetoothLyricProjection() {
        val stable = TrackIdentity(title = "Style", artist = "Taylor Swift", durationMs = 231_000L)
        val projection = TrackIdentity(
            title = "Midnight, you come and pick me up",
            artist = "Style - Taylor Swift",
            durationMs = 231_000L
        )
        val resolved = LxBluetoothLyricMetadataPolicy.resolve(stable, projection)
        assertNotNull(resolved)
        assertTrue(resolved.projection)
        assertSame(stable, resolved.track)
    }

    @Test
    fun recognizesProjectionWhenTrackPlayerPublishesAnEphemeralMediaId() {
        val stable = TrackIdentity(
            id = "stable-id",
            title = "Style",
            artist = "Taylor Swift",
            durationMs = 231_000L
        )
        val projection = TrackIdentity(
            id = "projection-id",
            title = "Midnight, you come and pick me up",
            artist = "Style - Taylor Swift",
            durationMs = 231_000L
        )
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, projection))
    }

    @Test
    fun recognizesWalnutProjectionWhenTheStableArtistIncludesAlbum() {
        val stable = TrackIdentity(title = "Style", artist = "Taylor Swift - 1989", durationMs = 231_000L)
        val projection = TrackIdentity(
            title = "You got that James Dean daydream look in your eye",
            artist = "Style - Taylor Swift",
            durationMs = 231_000L
        )
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, projection))
    }

    @Test
    fun recognizesProjectionForAnArtistlessTrack() {
        val stable = TrackIdentity(title = "Instrumental", artist = "", durationMs = 180_000L)
        val projection = TrackIdentity(title = "A lyric line", artist = "Instrumental", durationMs = 180_000L)
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, projection))
    }

    @Test
    fun recognizesBlankLyricLineProjectionWhileSeeking() {
        val stable = TrackIdentity(
            title = "Welcome To New York",
            artist = "Taylor Swift",
            durationMs = 212_000L
        )
        val projection = TrackIdentity(
            title = "",
            artist = "Welcome To New York - Taylor Swift",
            durationMs = 212_000L
        )
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, projection))
    }

    @Test
    fun keepsStableIdentityAfterEmptySetLyricWhenBluetoothTitlesContinue() {
        val stable = TrackIdentity(title = "Style", artist = "Taylor Swift", durationMs = 231_000L)
        val matchingShapeWithoutLyrics = TrackIdentity(
            title = "A lyric line",
            artist = "Style - Taylor Swift",
            durationMs = 231_000L
        )
        val blankProjection = TrackIdentity(
            title = "",
            artist = "Style - Taylor Swift",
            durationMs = 231_000L
        )
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, matchingShapeWithoutLyrics))
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, blankProjection))
        val retained = LxBluetoothLyricMetadataPolicy.resolve(stable, null)
        assertNotNull(retained)
        assertTrue(retained.projection)
        assertSame(stable, retained.track)
    }

    @Test
    fun recognizesFullBluetoothLyricTitleDump() {
        val stable = TrackIdentity(title = "Style", artist = "Taylor Swift", durationMs = 231_000L)
        val fullLyricTitle = TrackIdentity(
            title = "[00:00.000]Midnight, you come and pick me up\n[00:04.000]No headlights",
            artist = "Taylor Swift",
            durationMs = 231_000L
        )
        assertTrue(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, fullLyricTitle))
    }

    @Test
    fun preservesActualTrackChangesAndNormalMetadata() {
        val stable = TrackIdentity(
            id = "style-id",
            title = "Style",
            artist = "Taylor Swift",
            durationMs = 231_000L
        )
        val normal = TrackIdentity(
            id = "style-id",
            title = "Style",
            artist = "Taylor Swift",
            durationMs = 231_000L
        )
        val nextTrack = TrackIdentity(
            id = "welcome-id",
            title = "Welcome To New York",
            artist = "Taylor Swift",
            durationMs = 212_000L
        )

        assertFalse(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, normal))
        assertFalse(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(stable, nextTrack))
        assertNull(LxBluetoothLyricMetadataPolicy.resolve(null, null))
    }

    @Test
    fun publishedLyricLineEvidencePreventsOfficialLxTitleChurnFromBumpingTrack() {
        val stable = TrackIdentity(
            title = "I Knew It, I Knew You",
            artist = "Taylor Swift",
            durationMs = 178_000L
        )
        val projectedLine = TrackIdentity(
            title = "I watched you drive around the bend",
            // LX can briefly retain the previous song's composite artist during buffering.
            artist = "I Knew It, I Knew You - Taylor Swift",
            durationMs = 178_000L
        )
        val nextSongBySameArtist = TrackIdentity(
            title = "Style",
            artist = "Taylor Swift",
            durationMs = 231_000L
        )

        val resolved = LxBluetoothLyricMetadataPolicy.resolve(
            stable,
            projectedLine,
            titleMatchesPublishedLyric = true
        )
        assertNotNull(resolved)
        assertTrue(resolved.projection)
        assertSame(stable, resolved.track)
        assertFalse(LxBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
            stable,
            nextSongBySameArtist,
            titleMatchesPublishedLyric = false
        ))
    }

    @Test
    fun decodesANewSongAlreadyPublishedInLxArtistConcatWithoutThePreviousTitlePrefix() {
        val previous = TrackIdentity(title = "Try Try Try", artist = "The Kid LAROI")
        val nextProjection = TrackIdentity(
            title = "What doesn't kill you makes you stronger",
            artist = "Stronger (What Doesn't Kill You) - Kelly Clarkson",
            durationMs = 221_000L
        )
        val resolved = LxBluetoothLyricMetadataPolicy.resolve(previous, nextProjection)
        assertNotNull(resolved)
        assertTrue(resolved.projection)
        assertEquals("Stronger (What Doesn't Kill You)", resolved.track.title)
        assertEquals("Kelly Clarkson", resolved.track.artist)
        assertTrue(
            LxSessionIdentity.shouldRewrite(
                nextProjection.title,
                nextProjection.artist,
                resolved.track.title,
                resolved.track.artist
            )
        )
        assertFalse(
            LxSessionIdentity.shouldRewrite(
                resolved.track.title,
                resolved.track.artist,
                resolved.track.title,
                resolved.track.artist
            )
        )
        assertTrue(
            LxSessionIdentity.shouldRewrite(
                "Stronger (What Doesn't Kill You)",
                "Stronger (What Doesn't Kill You) - Kelly Clarkson",
                resolved.track.title,
                resolved.track.artist
            )
        )
    }
}
