/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KuGouTrackIdentityTest {

    @Test
    fun liteSanitizeKeepsLongWesternTitleAndTwoWordArtist() {
        val track = KuGouTrackIdentity.sanitize(
            hostPackage = KuGouPlayerConstants.LITE_PACKAGE,
            title = "I Knew It, I Knew You",
            artist = "Taylor Swift",
            album = "The Life of a Showgirl",
            durationMs = 240_000L,
            mediaId = null,
            songIdFromLyricInfo = "c0874801bdc82b969d9dde5f313e14b4"
        )
        assertEquals("I Knew It, I Knew You", track.title)
        assertEquals("Taylor Swift", track.artist)
        assertEquals("c0874801bdc82b969d9dde5f313e14b4", track.id)
        assertNotEquals("Swift", track.title)
    }

    @Test
    fun liteSanitizeStillDerivesEnglishCarLyricArtistTitleSlot() {
        val track = KuGouTrackIdentity.sanitize(
            hostPackage = KuGouPlayerConstants.LITE_PACKAGE,
            title = "These days I've been looking back on the lives we've had",
            artist = "Troye Sivan-She's the Best (Explicit)",
            album = "",
            durationMs = 0L,
            mediaId = null,
            songIdFromLyricInfo = null
        )
        assertEquals("She's the Best (Explicit)", track.title)
        assertEquals("Troye Sivan", track.artist)
    }

    @Test
    fun identifierNamespaceEnrichmentDoesNotAdvanceGeneration() {
        val fallback = KuGouTrackIdentity.sanitize(
            hostPackage = KuGouPlayerConstants.STANDARD_PACKAGE,
            title = "回家的路",
            artist = "HOYO-MiX",
            album = "原神-灼火之心",
            durationMs = 171_000L,
            mediaId = null,
            songIdFromLyricInfo = null
        )
        val withMediaId = KuGouTrackIdentity.sanitize(
            hostPackage = KuGouPlayerConstants.STANDARD_PACKAGE,
            title = "回家的路",
            artist = "HOYO-MiX",
            album = "原神-灼火之心",
            durationMs = 171_000L,
            mediaId = "media-namespace-id",
            songIdFromLyricInfo = null
        )
        val withSongId = KuGouTrackIdentity.sanitize(
            hostPackage = KuGouPlayerConstants.STANDARD_PACKAGE,
            title = "回家的路",
            artist = "HOYO-MiX",
            album = "原神-灼火之心",
            durationMs = 171_000L,
            mediaId = "media-namespace-id",
            songIdFromLyricInfo = "official-song-id"
        )

        assertNotEquals(fallback.id, withMediaId.id)
        assertNotEquals(withMediaId.id, withSongId.id)

        val policy = TrackGenerationPolicy()
        val generation = policy.onTrackObserved(KuGouTrackIdentity.generationIdentity(fallback))
        assertEquals(
            generation,
            policy.onTrackObserved(KuGouTrackIdentity.generationIdentity(withMediaId))
        )
        assertEquals(
            generation,
            policy.onTrackObserved(KuGouTrackIdentity.generationIdentity(withSongId))
        )

        val nextTrack = withSongId.copy(title = "星间旅行", id = "next-song-id")
        assertEquals(
            generation + 1L,
            policy.onTrackObserved(KuGouTrackIdentity.generationIdentity(nextTrack))
        )
    }
}
