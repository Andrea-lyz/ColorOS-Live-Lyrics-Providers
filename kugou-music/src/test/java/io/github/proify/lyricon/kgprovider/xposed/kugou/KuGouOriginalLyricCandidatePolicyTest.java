/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou;

import static org.junit.Assert.assertFalse;
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
}
