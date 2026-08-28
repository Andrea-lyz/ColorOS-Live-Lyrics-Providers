/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouConceptLyricSanitizePolicyTest {

    @Test
    fun excludesOnlyTheSystemUiFilteredLitePromoBanner() {
        assertTrue(
            KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine("[听歌就在中国酷狗*星耀计划]")
        )
        assertTrue(
            KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine("[ 听歌就在中国酷狗 * 星耀计划 ]")
        )
        assertFalse(KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine("听歌就在中国酷狗"))
        assertFalse(KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine("[星耀计划]"))
        assertFalse(KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine("[听歌就在中国酷狗]"))
    }
}
