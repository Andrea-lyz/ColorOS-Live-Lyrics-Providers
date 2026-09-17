/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import org.junit.Assert.assertEquals
import org.junit.Test

class KuWoLyricOverlayPolicyTest {

    @Test
    fun noLyricYetLeavesHostMetadataUntouched() {
        assertEquals(
            KuWoLyricOverlayAction.NOOP,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = false,
                trackMatches = false,
                currentValue = "previous-track-lyrics",
                newValue = null
            )
        )
    }

    @Test
    fun otherTrackLyricInfoIsClearedOnce() {
        assertEquals(
            KuWoLyricOverlayAction.CLEAR,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = true,
                trackMatches = false,
                currentValue = "{\"rawLyric\":\"stale\"}",
                newValue = null
            )
        )
        assertEquals(
            KuWoLyricOverlayAction.NOOP,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = true,
                trackMatches = false,
                currentValue = "",
                newValue = null
            )
        )
    }

    @Test
    fun matchingTrackAppendsOnlyWhenTheValueChanges() {
        assertEquals(
            KuWoLyricOverlayAction.APPEND,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = true,
                trackMatches = true,
                currentValue = null,
                newValue = "{\"rawLyric\":\"[00:01.00]a\"}"
            )
        )
        assertEquals(
            KuWoLyricOverlayAction.NOOP,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = true,
                trackMatches = true,
                currentValue = "{\"rawLyric\":\"[00:01.00]a\"}",
                newValue = "{\"rawLyric\":\"[00:01.00]a\"}"
            )
        )
    }

    @Test
    fun missingEncodedPayloadDoesNotWrite() {
        assertEquals(
            KuWoLyricOverlayAction.NOOP,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = true,
                trackMatches = true,
                currentValue = null,
                newValue = null
            )
        )
        assertEquals(
            KuWoLyricOverlayAction.NOOP,
            KuWoLyricOverlayPolicy.decide(
                songAvailable = true,
                trackMatches = true,
                currentValue = "x",
                newValue = ""
            )
        )
    }
}
