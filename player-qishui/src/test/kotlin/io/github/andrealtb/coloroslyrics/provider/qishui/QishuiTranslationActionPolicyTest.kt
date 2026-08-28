/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QishuiTranslationActionPolicyTest {
    @Test
    fun exposesOnlyForCurrentGenerationWithTranslations() {
        assertTrue(QishuiTranslationActionPolicy.shouldExpose(3L, 3L, 1))
        assertFalse(QishuiTranslationActionPolicy.shouldExpose(3L, 2L, 1))
        assertFalse(QishuiTranslationActionPolicy.shouldExpose(3L, 3L, 0))
        assertFalse(QishuiTranslationActionPolicy.shouldExpose(0L, 0L, 5))
    }
}
