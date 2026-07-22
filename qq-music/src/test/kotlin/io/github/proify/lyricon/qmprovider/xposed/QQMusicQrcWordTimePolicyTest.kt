/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicQrcWordTimePolicyTest {
    @Test
    fun keepsEarlyAbsoluteQrcWordTimesOnTheirOriginalTimeline() {
        val rawWordBegins = listOf(2_296L, 3_073L, 3_266L, 3_417L, 3_896L, 4_066L)

        val axis = QQMusicQrcWordTimePolicy.resolveForQQMusicQrc(
            lineBegin = 2_296L,
            lineEnd = 4_507L,
            lineDuration = 2_211L,
            rawWordBegins = rawWordBegins
        )

        assertFalse(axis.usesRelativeOffsets)
        assertEquals(rawWordBegins, rawWordBegins.map(axis::toAbsolute))
    }

    @Test
    fun convertsExplicitlyRelativeQrcWordOffsetsToAbsoluteTimes() {
        val rawWordBegins = listOf(0L, 779L, 943L, 1_121L, 1_595L, 1_740L)

        val axis = QQMusicQrcWordTimePolicy.resolveForQQMusicQrc(
            lineBegin = 6_835L,
            lineEnd = 8_928L,
            lineDuration = 2_093L,
            rawWordBegins = rawWordBegins
        )

        assertTrue(axis.usesRelativeOffsets)
        assertEquals(
            listOf(6_835L, 7_614L, 7_778L, 7_956L, 8_430L, 8_575L),
            rawWordBegins.map(axis::toAbsolute)
        )
    }
}
