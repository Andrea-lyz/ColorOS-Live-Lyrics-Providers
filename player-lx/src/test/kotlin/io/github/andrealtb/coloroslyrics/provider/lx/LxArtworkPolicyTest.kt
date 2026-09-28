/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LxArtworkPolicyTest {

    @Test
    fun remoteUriOnlyMetadataWaitsForHostBitmap() {
        assertFalse(
            LxArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("https://example.test/cover.jpg")
            )
        )
        assertTrue(
            LxArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = true,
                artworkUris = listOf("https://example.test/cover.jpg")
            )
        )
        assertTrue(LxArtworkPolicy.isReadyForLyricInfo(false, emptyList()))
        assertTrue(
            LxArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("content://media/cover")
            )
        )
        assertFalse(
            LxArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("custom-cover-without-supported-scheme")
            )
        )
    }
}
