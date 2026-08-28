/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouMetadataIdentityPolicyTest {

    @Test
    fun legitimateHyphenatedTitleWithArtistIsNotCarLyricMetadata() {
        assertFalse(
            KuGouMetadataIdentityPolicy.looksLikeCarLyricDisplayMetadata(
                "BATTER UP (Remix) - Bonus Track",
                "BABYMONSTER"
            )
        )
    }

    @Test
    fun titleContainingArtistIsCarLyricDisplayMetadata() {
        assertTrue(
            KuGouMetadataIdentityPolicy.looksLikeCarLyricDisplayMetadata(
                "BATTER UP (Remix) - Bonus Track - BABYMONSTER",
                "BABYMONSTER"
            )
        )
    }

    @Test
    fun derivesRealIdentityFromChurnedCarLyricMetadata() {
        val chinese = KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(
            "演唱: 马健涛",
            "对角音乐 逐梦滚烫"
        )
        assertNotNull(chinese)
        assertEquals("逐梦滚烫", chinese!!.realTitle)
        assertEquals("对角音乐", chinese.realArtist)

        val english = KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(
            "These days I've been looking back on the lives we've had",
            "Troye Sivan-She's the Best (Explicit)"
        )
        assertNotNull(english)
        assertEquals("She's the Best (Explicit)", english!!.realTitle)
        assertEquals("Troye Sivan", english.realArtist)
    }

    @Test
    fun keepsStableMetadataUntouched() {
        assertNull(KuGouMetadataIdentityPolicy.carLyricDerivedIdentity("Good Times", "Lukas Graham"))
        assertNull(
            KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(
                "She's the Best (Explicit)",
                "Troye Sivan-She's the Best (Explicit)"
            )
        )
        assertNull(KuGouMetadataIdentityPolicy.carLyricDerivedIdentity("明天过后", "张杰"))
    }

    @Test
    fun longWesternTitleAndTwoWordArtistIsNotCarLyricIdentity() {
        assertNull(
            KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(
                "I Knew It, I Knew You",
                "Taylor Swift"
            )
        )
        assertNull(
            KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(
                "I Can Do It With a Broken Heart",
                "Taylor Swift"
            )
        )
        assertNull(
            KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(
                "Highway to Hell",
                "AC-DC"
            )
        )
    }
}
