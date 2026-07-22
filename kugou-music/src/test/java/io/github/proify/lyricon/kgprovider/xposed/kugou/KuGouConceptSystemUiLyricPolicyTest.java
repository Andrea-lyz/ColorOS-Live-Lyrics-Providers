/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KuGouConceptSystemUiLyricPolicyTest {

    @Test
    public void excludesOnlyTheSystemUiFilteredLitePromoBanner() {
        assertTrue(KuGouConceptSystemUiLyricPolicy.INSTANCE.shouldExcludeTimedPromoLine(
                "[听歌就在中国酷狗*星耀计划]"));
        assertTrue(KuGouConceptSystemUiLyricPolicy.INSTANCE.shouldExcludeTimedPromoLine(
                "[ 听歌就在中国酷狗 * 星耀计划 ]"));
        assertFalse(KuGouConceptSystemUiLyricPolicy.INSTANCE.shouldExcludeTimedPromoLine(
                "听歌就在中国酷狗"));
        assertFalse(KuGouConceptSystemUiLyricPolicy.INSTANCE.shouldExcludeTimedPromoLine(
                "[星耀计划]"));
        assertFalse(KuGouConceptSystemUiLyricPolicy.INSTANCE.shouldExcludeTimedPromoLine(
                "[听歌就在中国酷狗]"));
    }
}
