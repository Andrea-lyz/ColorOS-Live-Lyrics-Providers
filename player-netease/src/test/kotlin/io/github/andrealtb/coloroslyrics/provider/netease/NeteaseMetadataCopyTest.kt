/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseMetadataCopyTest {

    @Test
    fun copiesHardwareOrOversizedBitmapsForBinder() {
        assertTrue(NeteaseMetadataCopy.shouldCopyForBinder("HARDWARE", 200, 200))
        assertTrue(NeteaseMetadataCopy.shouldCopyForBinder("ARGB_8888", 480, 480))
        assertFalse(NeteaseMetadataCopy.shouldCopyForBinder("ARGB_8888", 200, 200))
        assertFalse(NeteaseMetadataCopy.shouldCopyForBinder("ARGB_8888", 4, 4))
        assertEquals(2, NeteaseMetadataCopy.sampleSize(480, 480))
        assertEquals(1, NeteaseMetadataCopy.sampleSize(200, 200))
    }
}
