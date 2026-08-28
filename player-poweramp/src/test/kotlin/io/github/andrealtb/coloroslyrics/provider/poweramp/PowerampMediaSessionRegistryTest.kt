/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerampMediaSessionRegistryTest {
    private val track = TrackIdentity(id = "1", title = "Song A", artist = "Artist A")

    @Test
    fun mainSessionIsSelectedAndCastIsIgnored() {
        val registry = PowerampMediaSessionRegistry()
        val main = Any()
        val cast = Any()
        registry.onConstructed(main, PowerampPlayerConstants.MAIN_SESSION_TAG)
        registry.onConstructed(cast, PowerampPlayerConstants.CAST_SESSION_TAG)
        registry.onHostMetadata(main, track, "main")
        registry.onHostMetadata(cast, track, "cast")
        registry.onPlaybackState(main, PlaybackState.STATE_PLAYING)
        registry.onPlaybackState(cast, PlaybackState.STATE_PLAYING)
        registry.onActive(main, true)
        registry.onActive(cast, true)

        assertEquals(main, registry.selectUnique(track))
        assertEquals(track, registry.uniqueCurrentTrack())
        assertEquals(main, registry.uniqueLiveSession())
        assertFalse(registry.isCastSession(main))
        assertTrue(registry.isCastSession(cast))

        registry.onReleased(main)
        assertNull(registry.selectUnique(track))
        assertNull(registry.uniqueLiveSession())
    }

    @Test
    fun uniqueLiveSessionFallsBackBeforeActiveFlag() {
        val registry = PowerampMediaSessionRegistry()
        val session = Any()
        registry.onConstructed(session, "Poweramp")
        registry.onHostMetadata(session, track, "dummy")
        assertEquals(session, registry.uniqueLiveSession())
        assertNotNull(registry.selectUnique(track))
    }

    @Test
    fun withModuleWriteSetsAndRestoresGuard() {
        val registry = PowerampMediaSessionRegistry()
        var insideGuard = false
        registry.withModuleWrite {
            insideGuard = registry.isModuleWrite()
        }
        assertTrue(insideGuard)
        assertEquals(false, registry.isModuleWrite())
    }
}
