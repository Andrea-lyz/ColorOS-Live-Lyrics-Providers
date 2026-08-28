/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyArtworkPolicyTest {
    @Test
    fun uriOnlyIsReadyForLyricOverlay() {
        assertTrue(
            SpotifyArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("https://i.scdn.co/image/cover.jpg")
            )
        )
        assertTrue(
            SpotifyArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = true,
                artworkUris = listOf("https://example.com/cover.jpg")
            )
        )
        assertTrue(
            SpotifyArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf(null, "")
            )
        )
    }

    @Test
    fun hardwareOrOversizedBitmapsNeedBinderCopy() {
        assertTrue(SpotifyArtworkPolicy.shouldCopyForBinder("HARDWARE", 64, 64))
        assertTrue(SpotifyArtworkPolicy.shouldCopyForBinder("ARGB_8888", 512, 512))
        assertFalse(SpotifyArtworkPolicy.shouldCopyForBinder("ARGB_8888", 120, 120))
    }
}
