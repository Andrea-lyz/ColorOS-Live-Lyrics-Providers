/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcTimeFormatterTest {
    @Test
    fun formatsNegativeMillisAsZero() {
        assertEquals("00:00.000", LrcTimeFormatter.format(-150L))
    }

    @Test
    fun formatsWholeMinutes() {
        assertEquals("01:00.000", LrcTimeFormatter.format(60_000L))
    }

    @Test
    fun formatsSubSecondMillis() {
        assertEquals("00:00.123", LrcTimeFormatter.format(123L))
    }

    @Test
    fun formatsCompositePosition() {
        assertEquals("03:25.456", LrcTimeFormatter.format(205_456L))
    }
}
