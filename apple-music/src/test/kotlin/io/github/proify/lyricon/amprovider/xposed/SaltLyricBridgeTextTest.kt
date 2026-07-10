/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltLyricBridgeTextTest {
    @Test
    fun detectsAppleWordTokensThatDoNotMatchDisplayLine() {
        assertTrue(
            appleBridgeDisplayTextMismatch(
                lineText = "\uac81 \uc5c6\uc774",
                wordTexts = listOf("\uac81\ub3c4", " ", "\uc5c6\uc774")
            )
        )
    }

    @Test
    fun detectsMissingDisplaySpaceAndPunctuation() {
        assertTrue(
            appleBridgeDisplayTextMismatch(
                lineText = "\uac81 \uc5c6\uc774",
                wordTexts = listOf("\uac81", "\uc5c6\uc774")
            )
        )
        assertTrue(
            appleBridgeDisplayTextMismatch(
                lineText = "Walk my way!",
                wordTexts = listOf("Walk ", "my ", "way")
            )
        )
    }

    @Test
    fun acceptsExactRenderedDisplayText() {
        assertFalse(
            appleBridgeDisplayTextMismatch(
                lineText = "Walk my way!",
                wordTexts = listOf("Walk ", "my ", "way!")
            )
        )
    }
}
