/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerampArtworkPolicyTest {

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
