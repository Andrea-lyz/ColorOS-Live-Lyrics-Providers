/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LxArtworkPolicyTest {

    @Test
    fun hardwareOrOversizedGlideBitmapsMustBeCopiedForBinder() {
        assertTrue(LxArtworkPolicy.shouldCopyForBinder("HARDWARE", 512, 512))
        assertTrue(LxArtworkPolicy.shouldCopyForBinder("ARGB_8888", 512, 512))
        assertFalse(LxArtworkPolicy.shouldCopyForBinder("ARGB_8888", 240, 240))
        assertFalse(LxArtworkPolicy.shouldCopyForBinder("HARDWARE", 1, 1))
        assertEquals(4, LxArtworkPolicy.sampleSize(512, 512))
        assertEquals(1, LxArtworkPolicy.sampleSize(240, 240))
        assertTrue(LxArtworkPolicy.isPlausibleBitmapSize(300, 300))
        assertFalse(LxArtworkPolicy.isPlausibleBitmapSize(1, 1))
    }

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
