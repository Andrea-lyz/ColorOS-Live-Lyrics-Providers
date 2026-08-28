/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyPlayerConstantsTest {
    @Test
    fun qualifiedHostIsOfficialSpotifyMainProcessOnly() {
        assertEquals("com.spotify.music", SpotifyPlayerConstants.HOST_PACKAGE)
        assertContentEquals(
            arrayOf("com.spotify.music"),
            SpotifyPlayerConstants.QUALIFIED_HOST_PACKAGES
        )
        assertEquals(
            "io.github.andrealtb.coloroslyrics.provider.spotify",
            SpotifyPlayerConstants.MODULE_PACKAGE
        )
        assertTrue(SpotifyPlayerConstants.isPlaybackProcess("com.spotify.music"))
        assertFalse(SpotifyPlayerConstants.isPlaybackProcess("com.spotify.music:push"))
        assertFalse(SpotifyPlayerConstants.isPlaybackProcess("com.spotify.music:download"))
        assertFalse(SpotifyPlayerConstants.isCastSessionTag("Spotify"))
        assertTrue(SpotifyPlayerConstants.isCastSessionTag("CastMediaSession"))
        assertTrue(SpotifyPlayerConstants.isCastSessionTag("gms-cast"))
    }
}
