/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import io.github.andrealtb.coloroslyrics.provider.salt.SaltDexKitDiscovery.SaltDexKitFixture

class SaltDexKitDiscoveryTest {

    @Test
    fun exposesFixtureClassNamesFor12_3_0() {
        assertEquals(
            "androidx.media3.ac1",
            SaltDexKitFixture.sourceEnumName
        )
        assertEquals(
            "androidx.media3.bc1",
            SaltDexKitFixture.scrollEnumName
        )
        assertEquals(
            "androidx.media3.zb1",
            SaltDexKitFixture.lyricResultName
        )
        assertEquals(
            "androidx.media3.tv1",
            SaltDexKitFixture.publisherName
        )
        assertEquals(
            "迉",
            SaltDexKitFixture.publisherSuspendMethodName
        )
    }

    @Test
    fun requireSingleClassRejectsZeroAndMultipleCandidates() {
        assertFailsWith<IllegalStateException> {
            SaltDexKitDiscovery.requireSingleClassForTesting(
                "lyric source enum",
                emptyList()
            )
        }
        assertFailsWith<IllegalStateException> {
            SaltDexKitDiscovery.requireSingleClassForTesting(
                "lyric source enum",
                listOf("one", "two")
            )
        }
    }

    @Test
    fun singleCandidateIsReturned() {
        val result = SaltDexKitDiscovery.requireSingleClassForTesting(
            "lyric source enum",
            listOf("androidx.media3.ac1")
        )
        assertTrue(result.contains("androidx.media3.ac1"))
    }
}
