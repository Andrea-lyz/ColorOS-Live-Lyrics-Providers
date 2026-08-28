/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LxMediaSessionRegistryTest {
    private val track1 = TrackIdentity(id = "1", title = "Song A", artist = "Artist A")
    private val track2 = TrackIdentity(id = "2", title = "Song B", artist = "Artist B")

    @Test
    fun sessionLifecycleRegistersAndSelectsUniqueSession() {
        val registry = LxMediaSessionRegistry()
        val mockSession = Any()

        registry.onConstructed(mockSession, "MusicService")
        registry.onHostMetadata(mockSession, track1, "dummy_metadata")
        registry.onPlaybackState(mockSession, PlaybackState.STATE_PLAYING)
        registry.onActive(mockSession, true)

        val selected = registry.selectUnique(track1)
        assertNotNull(selected)
        assertEquals(mockSession, selected)
        assertEquals(track1, registry.uniqueCurrentTrack())

        registry.onReleased(mockSession)
        assertNull(registry.selectUnique(track1))
        assertNull(registry.uniqueCurrentTrack())
    }

    @Test
    fun uniqueLiveSessionFallsBackWhenActiveFlagHasNotArrived() {
        val registry = LxMediaSessionRegistry()
        val mockSession = Any()
        registry.onConstructed(mockSession, "MusicService")
        registry.onHostMetadata(mockSession, track1, "dummy_metadata")

        assertEquals(mockSession, registry.uniqueLiveSession())
        assertEquals(track1, registry.uniqueCurrentTrack())
        assertEquals(mockSession, registry.selectUnique(track1))
        assertNull(registry.selectUnique(track2))
    }

    @Test
    fun withModuleWriteSetsAndRestoresGuard() {
        val registry = LxMediaSessionRegistry()
        var insideGuard = false
        registry.withModuleWrite {
            insideGuard = registry.isModuleWrite()
        }
        assertTrue(insideGuard)
        assertEquals(false, registry.isModuleWrite())
    }
}
