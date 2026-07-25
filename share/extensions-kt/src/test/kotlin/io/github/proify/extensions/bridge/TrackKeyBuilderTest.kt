/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackKeyBuilderTest {
    @Test
    fun normalizeCollapsesWhitespaceAndLowercases() {
        assertEquals("hello world", TrackKeyBuilder.normalizeTrackComponent("  Hello   World  "))
    }

    @Test
    fun normalizeFoldsUnicodeApostrophesToAscii() {
        assertEquals("it's", TrackKeyBuilder.normalizeTrackComponent("It’s"))
        assertEquals("'it's'", TrackKeyBuilder.normalizeTrackComponent("\u2018It\u2019s\u2019"))
        assertEquals("it's", TrackKeyBuilder.normalizeTrackComponent("It\u02BCs"))
        assertEquals("it's", TrackKeyBuilder.normalizeTrackComponent("It\uff07s"))
    }

    @Test
    fun normalizeNullAndBlankInputs() {
        assertEquals("", TrackKeyBuilder.normalizeTrackComponent(null))
        assertEquals("", TrackKeyBuilder.normalizeTrackComponent("   "))
    }

    @Test
    fun buildProducesTitleArtistJoinedByPipe() {
        assertEquals("hello|world", TrackKeyBuilder.build("Hello", "World"))
    }

    @Test
    fun buildWithBlankTitleYieldsEmptyKey() {
        // Empty title means we cannot identify the track via this fallback.
        assertEquals("", TrackKeyBuilder.build("   ", "World"))
    }

    @Test
    fun buildNormalizesBothSides() {
        assertEquals("hello world|artist name", TrackKeyBuilder.build("  Hello   World ", "Artist  Name"))
    }
}
