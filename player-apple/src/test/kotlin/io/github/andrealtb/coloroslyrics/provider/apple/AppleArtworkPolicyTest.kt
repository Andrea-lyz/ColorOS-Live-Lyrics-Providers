/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleArtworkPolicyTest {
    @Test
    fun httpsUriOrMissingCoverIsReadyForLyricOverlay() {
        assertTrue(
            AppleArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("https://is1-ssl.mzstatic.com/image/thumb/cover.jpg")
            )
        )
        assertTrue(
            AppleArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = true,
                artworkUris = listOf("https://example.com/cover.jpg")
            )
        )
        assertTrue(
            AppleArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf(null, "")
            )
        )
    }

    @Test
    fun hardwareOrOversizedBitmapsNeedBinderCopy() {
        assertTrue(AppleArtworkPolicy.shouldCopyForBinder("HARDWARE", 64, 64))
        assertTrue(AppleArtworkPolicy.shouldCopyForBinder("ARGB_8888", 512, 512))
        assertFalse(AppleArtworkPolicy.shouldCopyForBinder("ARGB_8888", 120, 120))
    }
}
