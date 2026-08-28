/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouMetadataCopyTest {

    @Test
    fun copiesHardwareOrOversizedBitmapsForBinder() {
        assertTrue(KuGouMetadataCopy.shouldCopyForBinder("HARDWARE", 200, 200))
        assertTrue(KuGouMetadataCopy.shouldCopyForBinder("ARGB_8888", 480, 480))
        assertFalse(KuGouMetadataCopy.shouldCopyForBinder("ARGB_8888", 200, 200))
        assertFalse(KuGouMetadataCopy.shouldCopyForBinder("ARGB_8888", 4, 4))
        assertEquals(2, KuGouMetadataCopy.sampleSize(480, 480))
        assertEquals(1, KuGouMetadataCopy.sampleSize(200, 200))
    }
}
