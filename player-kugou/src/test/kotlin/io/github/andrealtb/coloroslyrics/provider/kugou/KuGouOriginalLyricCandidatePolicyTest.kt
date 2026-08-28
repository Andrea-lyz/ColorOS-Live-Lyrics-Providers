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

class KuGouOriginalLyricCandidatePolicyTest {

    @Test
    fun rejectsChineseVersionCallbackWhileKoreanVersionIsCurrent() {
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                "Catch Catch (Chinese Ver.) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"
            )
        )
    }

    @Test
    fun keepsExpectedTitleAndCapturedSongShortcut() {
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                "\uce90\uce58 \uce90\uce58 (Catch Catch) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"
            )
        )
        val sameSongId = "\ubc45\ubc45\ud589\ud589 (bang bang bang)|bigbang"
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                sameSongId,
                sameSongId,
                "BANG BANG BANG (\ubc45\ubc45\ud589\ud589) - BIGBANG (\ube45\ubc45)",
                "\ubc45\ubc45\ud589\ud589 (bang bang bang)",
                "BIGBANG"
            )
        )
    }

    @Test
    fun rejectsPrefetchedNextTrackFileIdentity() {
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Troye Sivan",
                "She's the Best (Explicit)",
                "Good Times",
                "Lukas Graham"
            )
        )
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Lukas Graham",
                "7 Years",
                "Good Times",
                "Lukas Graham"
            )
        )
    }

    @Test
    fun keepsCurrentAndCarLyricFileIdentity() {
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Lukas Graham",
                "Good Times",
                "Good Times",
                "Lukas Graham"
            )
        )
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Troye Sivan",
                "She's the Best (Explicit)",
                "These days I've been looking back on the lives we've had",
                "Troye Sivan-She\u2019s the Best (Explicit)"
            )
        )
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "BIGBANG (\ube45\ubc45)",
                "BANG BANG BANG (\ubc45\ubc45\ud589\ud589)",
                "\ubc45\ubc45\ud589\ud589 (bang bang bang)",
                "BIGBANG"
            )
        )
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Taylor Swift",
                "I Knew It, I Knew You",
                "I Knew It, I Knew You",
                "Taylor Swift"
            )
        )
    }

    @Test
    fun corruptedSwiftIdentityWouldRejectTheRealFile() {
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Taylor Swift",
                "I Knew It, I Knew You",
                "Swift",
                "Taylor"
            )
        )
    }

    @Test
    fun parsesArtistTitleFromKuGouFileNames() {
        val spaced = KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(
            "/data/user/0/com.kugou.android.lite/files/kugou/lyrics/" +
                "Lukas Graham - Good Times-318c587828be1b04d081f39ebbe2719d.krc"
        )
        assertNotNull(spaced)
        assertEquals("Lukas Graham", spaced!!.artist)
        assertEquals("Good Times", spaced.title)

        val dashedArtist = KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(
            "AC-DC - Highway to Hell-0123456789abcdef.krc"
        )
        assertNotNull(dashedArtist)
        assertEquals("AC-DC", dashedArtist!!.artist)
        assertEquals("Highway to Hell", dashedArtist.title)
        assertNull(
            KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(
                "Nightglow-318c587828be1b04d081f39ebfe2719d.krc"
            )
        )
    }
}
