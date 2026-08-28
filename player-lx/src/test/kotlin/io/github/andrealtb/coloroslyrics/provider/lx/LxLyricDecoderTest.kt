/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LxLyricDecoderTest {

    @Test
    fun enhancedLrcPreservesWordTimingAndAlignsTranslation() {
        val raw = "[00:01.000]<00:01.000>Ni<00:01.400>hao<00:02.000>\n" +
            "[00:03.000]<00:03.000>Hello <00:03.500>world<00:04.000>"
        val translation = "[00:01.000]Hello\n[00:03.000]Welcome"

        val publication = LxLyricDecoder.decode(raw, translation)

        assertNotNull(publication)
        assertEquals(raw, publication.rawLyric)
        assertEquals(2, publication.lines.size)
        assertEquals("Nihao", publication.lines[0].text)
        assertEquals("Hello", publication.lines[0].secondary)
        assertEquals("Hello world", publication.lines[1].text)
        assertEquals("Welcome", publication.lines[1].secondary)
        assertTrue(publication.lines[0].words.orEmpty().size >= 2)
    }

    @Test
    fun normalLrcStaysLineTimedWithoutSyntheticWordTiming() {
        val raw = "[00:01.000]First line\n[00:03.000]Second line"
        val publication = LxLyricDecoder.decode(raw, "")

        assertNotNull(publication)
        assertEquals(raw, publication.rawLyric)
        assertEquals("First line", publication.lines[0].text)
        assertNull(publication.lines[0].secondary)
        assertTrue(publication.lines[0].words.isNullOrEmpty() || publication.lines[0].words!!.size <= 1)
    }

    @Test
    fun untimedTextIsNotPublished() {
        assertNull(LxLyricDecoder.decode("plain lyric", "[00:01.000]translation"))
        assertFalse(LxLyricDecoder.containsTimedLrc(""))
        assertFalse(LxLyricDecoder.containsTimedLrc(null))
    }

    @Test
    fun romajiMustNotBecomeTheTranslationLane() {
        val raw = "[00:01.000]你好"
        val translation = "[00:01.000]Hello"
        val publication = LxLyricDecoder.decode(raw, translation)
        assertNotNull(publication)
        assertEquals("Hello", publication.lines.single().secondary)
        assertEquals(translation, publication.translationLyric)
    }

    @Test
    fun blankCapturedIdentityMayBindToFirstMetadata() {
        assertTrue(LxLyricDecoder.matchesTrackIdentity(null, TrackIdentity(id = "next")))
        assertTrue(LxLyricDecoder.matchesTrackIdentity(TrackIdentity(), TrackIdentity(id = "next")))
        assertTrue(
            LxLyricDecoder.matchesTrackIdentity(
                TrackIdentity(id = "current"),
                TrackIdentity(id = "current")
            )
        )
        assertFalse(
            LxLyricDecoder.matchesTrackIdentity(
                TrackIdentity(id = "previous"),
                TrackIdentity(id = "next")
            )
        )
    }
}
