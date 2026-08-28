/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionAmbiguityException
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionNotFoundException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LxLyricModuleResolverTest {

    @Test
    fun findsUniqueSetLyricMethod() {
        val method = LxLyricModuleResolver.findSetLyricMethod(OfficialLyricModule::class.java)
        assertEquals("setLyric", method.name)
        assertEquals(4, method.parameterCount)
    }

    @Test
    fun rejectsMissingAndAmbiguousSetLyricMethods() {
        assertFailsWith<ReflectionNotFoundException> {
            LxLyricModuleResolver.findSetLyricMethod(NoLyricModule::class.java)
        }
        assertFailsWith<ReflectionAmbiguityException> {
            LxLyricModuleResolver.findSetLyricMethod(AmbiguousLyricModule::class.java)
        }
    }

    @Test
    fun blankPendingSurvivesHostArrivalAndHintedPendingDropsOnTrackChange() {
        val previous = TrackIdentity(id = "a", title = "A", artist = "Artist")
        val next = TrackIdentity(id = "b", title = "B", artist = "Artist")
        assertFalse(LxLyricModuleResolver.shouldDropPendingOnTrackChange(null, next))
        assertFalse(LxLyricModuleResolver.shouldDropPendingOnTrackChange(TrackIdentity(), next))
        assertFalse(LxLyricModuleResolver.shouldDropPendingOnTrackChange(previous, previous))
        assertTrue(LxLyricModuleResolver.shouldDropPendingOnTrackChange(previous, next))
    }

    @Suppress("unused")
    private class OfficialLyricModule {
        fun setLyric(lyric: String, translation: String, roma: String, promise: Any?) = Unit
    }

    @Suppress("unused")
    private class NoLyricModule {
        fun play(time: Int) = Unit
    }

    @Suppress("unused")
    private class AmbiguousLyricModule {
        fun setLyric(lyric: String, translation: String, roma: String) = Unit
        fun setLyric(lyric: String, translation: String, roma: String, promise: Any?) = Unit
    }
}
