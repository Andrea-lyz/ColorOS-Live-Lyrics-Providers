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
}
