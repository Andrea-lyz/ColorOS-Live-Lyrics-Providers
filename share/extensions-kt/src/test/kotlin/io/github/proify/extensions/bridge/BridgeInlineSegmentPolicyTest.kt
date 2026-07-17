/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeInlineSegmentPolicyTest {
    @Test
    fun qqStandaloneWhitespaceSequenceKeepsOfficialDisplaySpacing() {
        val builder = StringBuilder("[00:04.258]")
        listOf(
            "00:04.258" to "也",
            "00:04.478" to " ",
            "00:04.698" to "也",
            "00:04.918" to " ",
            "00:05.138" to "也"
        ).forEach { (time, segment) ->
            if (!BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, segment)) {
                builder.append('<').append(time).append('>').append(segment)
            }
        }

        assertEquals(
            "[00:04.258]<00:04.258>也 <00:04.698>也 <00:05.138>也",
            builder.toString()
        )
    }

    @Test
    fun standaloneTimedWhitespaceIsAttachedToPreviousVisibleSegment() {
        val builder = StringBuilder("[00:04.258]<00:04.258>也")

        assertTrue(BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, " "))
        assertEquals("[00:04.258]<00:04.258>也 ", builder.toString())
    }

    @Test
    fun repeatedWhitespaceTokensCollapseToOneSeparator() {
        val builder = StringBuilder("[00:04.258]<00:04.258>也")

        assertTrue(BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, "\t"))
        assertTrue(BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, "  "))
        assertEquals("[00:04.258]<00:04.258>也 ", builder.toString())
    }

    @Test
    fun leadingWhitespaceDoesNotCreateAVisiblePrefix() {
        val builder = StringBuilder("[00:04.258]")

        assertTrue(BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, " "))
        assertEquals("[00:04.258]", builder.toString())
    }

    @Test
    fun visibleWordStillUsesItsOwnTimeTag() {
        val builder = StringBuilder("[00:04.258]")

        assertFalse(BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, "也"))
        assertEquals("[00:04.258]", builder.toString())
    }
}
