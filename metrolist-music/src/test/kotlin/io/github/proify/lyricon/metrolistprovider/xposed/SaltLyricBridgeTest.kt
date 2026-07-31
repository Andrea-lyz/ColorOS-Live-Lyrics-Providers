/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class SaltLyricBridgeTest {
    @Test
    fun sanitizerPreservesEveryInlineWordTimestamp() {
        val enhanced =
            "[00:01.000]<00:01.000>Hello <00:01.400>world<00:02.000>\n" +
                "[00:02.500]{agent:v1}<00:02.500>Again<00:03.000>"

        assertEquals(
            enhanced.replace("{agent:v1}", ""),
            SaltLyricBridge.sanitizeExtendedLrc(enhanced)
        )
    }
}
