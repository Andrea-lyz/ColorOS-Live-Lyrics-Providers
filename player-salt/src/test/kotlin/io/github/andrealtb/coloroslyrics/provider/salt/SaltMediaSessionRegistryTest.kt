/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SaltMediaSessionRegistryTest {
    private fun track(id: String, title: String = "Title", artist: String = "Artist") =
        TrackIdentity(id = id, title = title, artist = artist, durationMs = 180000L)

    @Test
    fun uniqueActiveMatchingSessionIsSelectedAndAuxiliaryIsExcluded() {
        val registry = SaltMediaSessionRegistry()
        val main = Any()
        val auxiliary = Any()
        val wanted = track("1")
        activate(registry, main, wanted, "main-metadata")
        activate(registry, auxiliary, track("other"), "aux-metadata")

        assertSame(main, registry.selectUnique(wanted))
        assertSame("main-metadata", registry.hostMetadata(main))
    }

    @Test
    fun sameTrackSessionAmbiguityFailsOpenAndReleasedSessionIsIgnored() {
        val registry = SaltMediaSessionRegistry()
        val first = Any()
        val second = Any()
        val wanted = track("1")
        activate(registry, first, wanted, "first")
        activate(registry, second, wanted, "second")

        assertNull(registry.selectUnique(wanted))
        registry.onReleased(second)
        assertSame(first, registry.selectUnique(wanted))
    }

    @Test
    fun stableResolvedIdentityPreventsRelayLineGenerationChurn() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        val resolved = track("song-1", "Broken", "William Black/Fairlane")
        activate(registry, session, resolved, "relay-line-1")
        val controller = SaltHostGenerationController(registry)
        val firstGeneration = controller.observeUniqueHostMainTrack()

        registry.onHostMetadata(session, resolved, "relay-line-2")
        val secondGeneration = controller.observeUniqueHostMainTrack()
        registry.onHostMetadata(session, resolved, "relay-line-3")
        val thirdGeneration = controller.observeUniqueHostMainTrack()

        assertEquals(1L, firstGeneration)
        assertEquals(firstGeneration, secondGeneration)
        assertEquals(firstGeneration, thirdGeneration)
        assertSame("relay-line-3", registry.hostMetadata(session))
    }

    @Test
    fun realTrackChangeAdvancesGenerationAndRejectsOldCallback() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        activate(registry, session, track("old"), "old-metadata")
        val controller = SaltHostGenerationController(registry)
        val oldGeneration = controller.observeUniqueHostMainTrack()!!

        val current = track("new", "New Title", "New Artist")
        registry.onHostMetadata(session, current, "new-metadata")
        val newGeneration = controller.observeUniqueHostMainTrack()!!

        assertTrue(newGeneration > oldGeneration)
        assertFalse(controller.acceptsPublication(track("old"), oldGeneration))
        assertTrue(controller.acceptsPublication(current, newGeneration))
    }

    @Test
    fun pendingPublicationBecomesPublishableAfterFirstResolvedMetadata() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        registry.onConstructed(session, "main")
        registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true)
        val controller = SaltHostGenerationController(registry)
        val wanted = track("song-1", "Broken", "William Black/Fairlane")

        assertEquals(
            SaltPendingPublicationPolicy.Decision.PENDING,
            SaltPendingPublicationPolicy.decide(wanted, null, false, false, false)
        )

        registry.onHostMetadata(session, wanted, "first-relay-metadata")
        val generation = controller.observeUniqueHostMainTrack()!!
        assertEquals(
            SaltPendingPublicationPolicy.Decision.PUBLISH,
            SaltPendingPublicationPolicy.decide(
                wanted,
                controller.policy.currentTrack,
                controller.acceptsPublication(wanted, generation),
                registry.selectUnique(wanted) === session,
                registry.hostMetadata(session) != null
            )
        )
    }

    @Test
    fun moduleWriteGuardIsReentrantAndRestored() {
        val registry = SaltMediaSessionRegistry()
        assertFalse(registry.isModuleWrite())
        registry.withModuleWrite {
            assertTrue(registry.isModuleWrite())
            registry.withModuleWrite { assertTrue(registry.isModuleWrite()) }
            assertTrue(registry.isModuleWrite())
        }
        assertFalse(registry.isModuleWrite())
    }

    private fun activate(
        registry: SaltMediaSessionRegistry,
        session: Any,
        track: TrackIdentity,
        metadata: Any
    ) {
        registry.onConstructed(session, "main")
        registry.onHostMetadata(session, track, metadata)
        registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true)
    }
}
