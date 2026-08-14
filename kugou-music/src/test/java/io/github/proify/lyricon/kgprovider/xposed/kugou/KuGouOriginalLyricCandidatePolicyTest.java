/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KuGouOriginalLyricCandidatePolicyTest {

    @Test
    public void rejectsChineseVersionCallbackWhileKoreanVersionIsCurrent() {
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                "Catch Catch (Chinese Ver.) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"));
    }

    @Test
    public void rejectsKoreanCallbackWhileChineseVersionIsCurrent() {
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                "\uce90\uce58 \uce90\uce58 (Catch Catch) - YENA",
                "Catch Catch (Chinese Ver.)",
                "YENA"));
    }

    @Test
    public void keepsTheExpectedTitleAndOrdinaryLyricLines() {
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                "\uce90\uce58 \uce90\uce58 (Catch Catch) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"));
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                "YENA, catch my heart",
                "\uce90\uce58 \uce90\uce58",
                "YENA"));
    }

    @Test
    public void keepsKoreanCallbackWhenCapturedSongMatchesCurrent() {
        // BANG BANG BANG scenario: the KRC leading line mixes Hangul and
        // Romanised title while the MediaSession only exposes one of them,
        // so the strict contains check fails.  The capturedSongId fast path
        // must short-circuit and keep the candidate.
        final String sameSongId = "\ubc45\ubc45\ud589\ud589 (bang bang bang)|bigbang";
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                sameSongId,
                sameSongId,
                "BANG BANG BANG (\ubc45\ubc45\ud589\ud589) - BIGBANG (\ube45\ubc45)",
                "\ubc45\ubc45\ud589\ud589 (bang bang bang)",
                "BIGBANG"));
    }

    @Test
    public void rejectsForeignCallbackEvenWhenCapturedSongLooksSimilar() {
        // Captured under the previous track — the capturedSongId fast path
        // must not let the candidate through.  Use a non-Chinese leading line
        // so the policy-level rejection still fires (mirrors the existing
        // Catch Catch scenario).
        final String currentSongId = "different track id";
        final String capturedSongId = "\uce90\uce58 \uce90\uce58 (Catch Catch)|yena";
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                capturedSongId,
                currentSongId,
                "Catch Catch (Chinese Ver.) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"));
    }

    @Test
    public void fallsThroughToPolicyWhenCapturedSongIdIsBlank() {
        // When the caller has no capturedSongId we should still benefit from
        // the policy-level rejection and not silently accept everything.
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.hasForeignLeadingMetadata(
                null,
                null,
                "Catch Catch (Chinese Ver.) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"));
    }

    @Test
    public void rejectsPrefetchedNextTrackFileIdentity() {
        // She's the Best KRC prefetched while Good Times is current
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Troye Sivan",
                "She's the Best (Explicit)",
                "Good Times",
                "Lukas Graham"));
        // That Way KRC prefetched while She's the Best (car-lyric churn) is current
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "KATSEYE",
                "That Way",
                "These days I've been looking back on the lives we've had",
                "Troye Sivan-She\u2019s the Best (Explicit)"));
        // Good Times KRC while Tastes Like Summer is current
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Lukas Graham",
                "Good Times",
                "Tastes Like Summer",
                "James Blunt"));
    }

    @Test
    public void keepsCurrentTrackFileIdentity() {
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Lukas Graham", "Good Times", "Good Times", "Lukas Graham"));
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "James Blunt", "Tastes Like Summer", "Tastes Like Summer", "James Blunt"));
        // CJK file naming without spaces around the separator
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "\u9a6c\u5065\u6d9b", "\u6400\u6276", "\u6400\u6276", "\u9a6c\u5065\u6d9b"));
    }

    @Test
    public void keepsFileIdentityWhenCarLyricMetadataChurns() {
        // The artist field mixes "Artist-Title" and the title field holds a lyric
        // line, so the artist containment check must keep the correct file.
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Troye Sivan",
                "She's the Best (Explicit)",
                "These days I've been looking back on the lives we've had",
                "Troye Sivan-She\u2019s the Best (Explicit)"));
    }

    @Test
    public void cannotJudgeBlankFileIdentityIsNotForeign() {
        // Unparseable file names must not be rejected: keep legacy behavior.
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "", "", "Good Times", "Lukas Graham"));
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Lukas Graham", "", "Good Times", "Lukas Graham"));
    }

    @Test
    public void rejectsSameArtistNextTrackFileIdentity() {
        // Album playback: "Lukas Graham - 7 Years" prefetched while "Good Times"
        // is current.  Artist-only containment must not rescue it.
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Lukas Graham", "7 Years", "Good Times", "Lukas Graham"));
    }

    @Test
    public void rejectsDifferentArtistSubstringTitle() {
        // "Angel" (Sarah McLachlan) while "Angel Baby" (Troye Sivan) is current:
        // the title is a substring of the current title but the artists differ.
        assertTrue(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Sarah McLachlan", "Angel", "Angel Baby", "Troye Sivan"));
    }

    @Test
    public void keepsMixedScriptTitleFileIdentity() {
        // BANG BANG BANG: the KRC file name mixes Hangul and Romanised text while
        // the metadata exposes one script; the significant-token rescue must keep it.
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "BIGBANG (\ube45\ubc45)",
                "BANG BANG BANG (\ubc45\ubc45\ud589\ud589)",
                "\ubc45\ubc45\ud589\ud589 (bang bang bang)",
                "BIGBANG"));
    }

    @Test
    public void keepsBlankTitleArtistRescue() {
        assertFalse(KuGouOriginalLyricCandidatePolicy.INSTANCE.isForeignFileIdentity(
                "Lukas Graham", "Good Times", "", "Lukas Graham"));
    }

    @Test
    public void parsesArtistTitleFromKuGouFileNames() {
        KuGouOriginalLyricCandidatePolicy.FileIdentity spaced =
                KuGouOriginalLyricCandidatePolicy.INSTANCE.fileIdentityFromPath(
                        "/data/user/0/com.kugou.android.lite/files/kugou/lyrics/"
                                + "Lukas Graham - Good Times-318c587828be1b04d081f39ebbe2719d.krc");
        assertNotNull(spaced);
        assertEquals("Lukas Graham", spaced.getArtist());
        assertEquals("Good Times", spaced.getTitle());

        // CJK naming without spaces around the separator
        KuGouOriginalLyricCandidatePolicy.FileIdentity cjk =
                KuGouOriginalLyricCandidatePolicy.INSTANCE.fileIdentityFromPath(
                        "/data/user/0/com.kugou.android.lite/files/kugou/lyrics/"
                                + "\u9a6c\u5065\u6d9b-\u6400\u6276-5f3672bfed1c57fe63fcbe000d590db0.krc");
        assertNotNull(cjk);
        assertEquals("\u9a6c\u5065\u6d9b", cjk.getArtist());
        assertEquals("\u6400\u6276", cjk.getTitle());

        // Artist with a dash splits on the spaced separator
        KuGouOriginalLyricCandidatePolicy.FileIdentity dashedArtist =
                KuGouOriginalLyricCandidatePolicy.INSTANCE.fileIdentityFromPath(
                        "AC-DC - Highway to Hell-0123456789abcdef.krc");
        assertNotNull(dashedArtist);
        assertEquals("AC-DC", dashedArtist.getArtist());
        assertEquals("Highway to Hell", dashedArtist.getTitle());

        // Title with a dash keeps the full title after the first separator
        KuGouOriginalLyricCandidatePolicy.FileIdentity dashedTitle =
                KuGouOriginalLyricCandidatePolicy.INSTANCE.fileIdentityFromPath(
                        "YENA - Catch Catch (Korean Ver.) - Original-0123456789abcdef.krc");
        assertNotNull(dashedTitle);
        assertEquals("YENA", dashedTitle.getArtist());
        assertEquals("Catch Catch (Korean Ver.) - Original", dashedTitle.getTitle());
    }

    @Test
    public void unparseableFileNamesReturnNullIdentity() {
        assertNull(KuGouOriginalLyricCandidatePolicy.INSTANCE.fileIdentityFromPath(
                "Nightglow-318c587828be1b04d081f39ebbe2719d.krc"));
        assertNull(KuGouOriginalLyricCandidatePolicy.INSTANCE.fileIdentityFromPath(
                "-hashonly-318c587828be1b04d081f39ebbe2719d.krc"));
    }
}
