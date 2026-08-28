/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConeTrackMetadataExtractorTest {

    private class MockMetadataEntry(val key: String, val value: String)

    @Test
    fun extractTimedLyricFromMetadataEntry_withValidKeyAndTimedValue_extractsLyric() {
        val entry = MockMetadataEntry("LYRICS", "[00:01.00]Hello world")
        val result = ConeTrackMetadataExtractor.extractTimedLyricFromMetadataEntry(entry)
        assertEquals("[00:01.00]Hello world", result)
    }

    @Test
    fun extractTimedLyricFromMetadataEntry_withInvalidKey_returnsNull() {
        val entry = MockMetadataEntry("COMMENT", "[00:01.00]Hello world")
        val result = ConeTrackMetadataExtractor.extractTimedLyricFromMetadataEntry(entry)
        assertNull(result)
    }

    @Test
    fun extractTimedLyricFromMetadataEntry_withPlaceholder_returnsNull() {
        val entry = MockMetadataEntry("LYRICS", "[00:00.00]暂无歌词")
        val result = ConeTrackMetadataExtractor.extractTimedLyricFromMetadataEntry(entry)
        assertNull(result)
    }
}
