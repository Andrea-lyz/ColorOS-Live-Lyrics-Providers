/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConeMediaSessionRegistryTest {

    private val track1 = TrackIdentity(id = "1", title = "Song A", artist = "Artist A")
    private val track2 = TrackIdentity(id = "2", title = "Song B", artist = "Artist B")

    @Test
    fun sessionLifecycle_registersAndSelectsUniqueSession() {
        val registry = ConeMediaSessionRegistry()
        val mockSession = Any()

        registry.onConstructed(mockSession, "test_session")
        registry.onHostMetadata(mockSession, track1, "dummy_metadata")
        registry.onPlaybackState(mockSession, PlaybackState.STATE_PLAYING)
        registry.onActive(mockSession, true)

        val selected = registry.selectUnique(track1)
        assertNotNull(selected)
        assertEquals(mockSession, selected)
        assertEquals(track1, registry.uniqueCurrentTrack())

        // Release session
        registry.onReleased(mockSession)
        assertNull(registry.selectUnique(track1))
        assertNull(registry.uniqueCurrentTrack())
    }

    @Test
    fun withModuleWrite_setsAndRestoresGuard() {
        val registry = ConeMediaSessionRegistry()
        var insideGuard = false
        registry.withModuleWrite {
            insideGuard = registry.isModuleWrite()
        }
        assertTrue(insideGuard)
        assertEquals(false, registry.isModuleWrite())
    }
}
