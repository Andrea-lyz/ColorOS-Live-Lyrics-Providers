/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerampArtworkPolicyTest {
    @Test
    fun hardwareOrOversizedBitmapsMustBeCopiedForBinder() {
        assertTrue(PowerampArtworkPolicy.shouldCopyForBinder("HARDWARE", 512, 512))
        assertTrue(PowerampArtworkPolicy.shouldCopyForBinder("ARGB_8888", 512, 512))
        assertFalse(PowerampArtworkPolicy.shouldCopyForBinder("ARGB_8888", 240, 240))
        assertFalse(PowerampArtworkPolicy.shouldCopyForBinder("HARDWARE", 1, 1))
        assertEquals(4, PowerampArtworkPolicy.sampleSize(512, 512))
        assertEquals(1, PowerampArtworkPolicy.sampleSize(240, 240))
        assertTrue(PowerampArtworkPolicy.isPlausibleBitmapSize(300, 300))
        assertFalse(PowerampArtworkPolicy.isPlausibleBitmapSize(1, 1))
    }

    @Test
    fun placeholderUriWithoutBitmapWaitsForPhaseTwoCover() {
        assertFalse(
            PowerampArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("android.resource://com.maxmpz.audioplayer/drawable/aa_default")
            )
        )
        assertFalse(
            PowerampArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = false,
                artworkUris = listOf("content://com.maxmpz.audioplayer.aa/files/12")
            )
        )
        assertTrue(
            PowerampArtworkPolicy.isReadyForLyricInfo(
                hasPlausibleBitmap = true,
                artworkUris = listOf("android.resource://com.maxmpz.audioplayer/drawable/aa_default")
            )
        )
        assertTrue(PowerampArtworkPolicy.isReadyForLyricInfo(false, emptyList()))
        assertTrue(PowerampArtworkPolicy.isReadyForLyricInfo(false, listOf(null, "")))
    }
}
