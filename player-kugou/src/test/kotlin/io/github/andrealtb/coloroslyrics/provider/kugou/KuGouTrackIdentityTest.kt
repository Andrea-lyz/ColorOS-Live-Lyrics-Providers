/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

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
}
