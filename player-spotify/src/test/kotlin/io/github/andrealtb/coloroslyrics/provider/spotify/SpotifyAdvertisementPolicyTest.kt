/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyAdvertisementPolicyTest {
    @Test
    fun matchesTitleArtistAndFlag() {
        assertTrue(SpotifyAdvertisementPolicy.isAdvertisement("Advertisement", "Spotify"))
        assertTrue(SpotifyAdvertisementPolicy.isAdvertisement("Sponsored", "Brand"))
        assertTrue(SpotifyAdvertisementPolicy.isAdvertisement("广告", "Spotify"))
        assertTrue(
            SpotifyAdvertisementPolicy.isAdvertisement(
                title = "Song",
                artist = "Artist",
                advertisementFlag = 1L
            )
        )
        assertFalse(SpotifyAdvertisementPolicy.isAdvertisement("Cruel Summer", "Taylor Swift"))
    }
}
