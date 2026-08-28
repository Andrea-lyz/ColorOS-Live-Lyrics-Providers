/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerampLyricDecoderTest {
    @Test
    fun enhancedLrcPreservesWordTimingAndAlignsTranslation() {
        val raw = "[00:01.000]<00:01.000>Ni<00:01.400>hao<00:02.000>\n" +
            "[00:03.000]<00:03.000>Hello <00:03.500>world<00:04.000>"
        val translation = "[00:01.000]Hello\n[00:03.000]Welcome"
        val publication = PowerampLyricDecoder.decode(raw, translation)
        assertNotNull(publication)
        assertEquals(2, publication.lines.size)
        assertEquals("Hello", publication.lines[0].secondary)
        assertTrue(publication.lines[0].words.orEmpty().size >= 2)
    }

    @Test
    fun untimedTextIsNotPublished() {
        assertNull(PowerampLyricDecoder.decode("plain lyric"))
        assertFalse(PowerampLyricDecoder.containsTimedLrc(""))
        assertFalse(PowerampLyricDecoder.containsTimedLrc(null))
    }

    @Test
    fun sameTimestampLinesBecomeTranslation() {
        val raw = "[00:01.000]你好\n[00:01.000]Hello"
        val publication = PowerampLyricDecoder.decode(raw)
        assertNotNull(publication)
        assertEquals("你好", publication.lines.single().text)
        assertEquals("Hello", publication.lines.single().secondary)
    }

    @Test
    fun blankCapturedIdentityMayBindToFirstMetadata() {
        assertTrue(PowerampLyricDecoder.matchesTrackIdentity(null, TrackIdentity(id = "next")))
        assertTrue(
            PowerampLyricDecoder.matchesTrackIdentity(
                TrackIdentity(id = "current"),
                TrackIdentity(id = "current")
            )
        )
        assertFalse(
            PowerampLyricDecoder.matchesTrackIdentity(
                TrackIdentity(id = "previous"),
                TrackIdentity(id = "next")
            )
        )
    }
}
