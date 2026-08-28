/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetrolistArtworkPolicyTest {
    @Test
    fun uriOnlyIsNotReadyUntilBitmapArrives() {
        assertFalse(
            MetrolistArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("https://example.com/cover.jpg")
            )
        )
        assertTrue(
            MetrolistArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = true,
                artworkUris = listOf("https://example.com/cover.jpg")
            )
        )
        assertTrue(
            MetrolistArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf(null, "")
            )
        )
    }

    @Test
    fun hardwareOrOversizedBitmapsNeedBinderCopy() {
        assertTrue(MetrolistArtworkPolicy.shouldCopyForBinder("HARDWARE", 64, 64))
        assertTrue(MetrolistArtworkPolicy.shouldCopyForBinder("ARGB_8888", 512, 512))
        assertFalse(MetrolistArtworkPolicy.shouldCopyForBinder("ARGB_8888", 120, 120))
    }
}

