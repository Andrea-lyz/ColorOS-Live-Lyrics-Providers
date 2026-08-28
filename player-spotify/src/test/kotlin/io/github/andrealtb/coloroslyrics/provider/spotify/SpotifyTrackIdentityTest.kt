/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyTrackIdentityTest {
    @Test
    fun extractsStableTrackUri() {
        val parsed = SpotifyTrackIdentity.parseMediaId(
            "spotify:track:4cOdK2wGLETKBW3PvgPWqT"
        )
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", parsed?.uri)
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", parsed?.rawId)
        assertTrue(parsed!!.isTrack)
        assertEquals(
            "4cOdK2wGLETKBW3PvgPWqT",
            SpotifyTrackIdentity.rawTrackId("spotify:track:4cOdK2wGLETKBW3PvgPWqT")
        )
    }

    @Test
    fun ignoresEpisodesShowsAndBlankTrackSuffix() {
        assertEquals(
            SpotifyMediaKind.EPISODE,
            SpotifyTrackIdentity.parseMediaId("spotify:episode:abc")?.kind
        )
        assertEquals(
            SpotifyMediaKind.SHOW,
            SpotifyTrackIdentity.parseMediaId("spotify:show:abc")?.kind
        )
        assertFalse(SpotifyTrackIdentity.parseMediaId("spotify:episode:abc")!!.isTrack)
        assertNull(SpotifyTrackIdentity.parseMediaId("spotify:track:"))
        assertNull(SpotifyTrackIdentity.parseMediaId(""))
        assertNull(SpotifyTrackIdentity.parseMediaId(null))
        assertNull(SpotifyTrackIdentity.rawTrackId("spotify:episode:abc"))
    }
}
