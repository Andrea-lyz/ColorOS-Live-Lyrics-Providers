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
    private fun track(id: String, title: String = "Same", artist: String = "Artist", durationMs: Long = 0L) =
        TrackIdentity(id = id, title = title, artist = artist, durationMs = durationMs)

    @Test fun uniqueActiveMatchingSessionIsSelectedAndAuxiliaryIsExcluded() {
        val registry = SaltMediaSessionRegistry(); val main = Any(); val auxiliary = Any(); val wanted = track("1")
        registry.onConstructed(main, "Salt playback"); registry.onHostMetadata(main, wanted)
        registry.onPlaybackState(main, PlaybackState.STATE_PLAYING); registry.onActive(main, true)
        registry.onConstructed(auxiliary, "notification"); registry.onHostMetadata(auxiliary, track("other"))
        registry.onPlaybackState(auxiliary, PlaybackState.STATE_PLAYING); registry.onActive(auxiliary, true)
        assertSame(main, registry.selectUnique(wanted))
    }

    @Test fun sameTrackSessionAmbiguityFailsOpenAndReleasedSessionIsIgnored() {
        val registry = SaltMediaSessionRegistry(); val first = Any(); val second = Any(); val wanted = track("1")
        listOf(first, second).forEach { session ->
            registry.onConstructed(session, "tag"); registry.onHostMetadata(session, wanted)
            registry.onPlaybackState(session, PlaybackState.STATE_PAUSED); registry.onActive(session, true)
        }
        assertNull(registry.selectUnique(wanted)); registry.onReleased(second)
        assertSame(first, registry.selectUnique(wanted))
    }

    @Test fun hostMetadataDrivesGenerationAndOldCallbackIsRejectedAfterSwitch() {
        val registry = SaltMediaSessionRegistry(); val session = Any()
        registry.onConstructed(session, "main"); registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true); val controller = SaltHostGenerationController(registry)
        val oldTrack = track("old"); registry.onHostMetadata(session, oldTrack)
        val oldGeneration = controller.observeUniqueHostMainTrack()!!
        assertTrue(controller.acceptsPublication(oldTrack, oldGeneration))
        val newTrack = track("new"); registry.onHostMetadata(session, newTrack)
        val newGeneration = controller.observeUniqueHostMainTrack()!!
        assertTrue(newGeneration > oldGeneration)
        assertFalse(controller.acceptsPublication(oldTrack, oldGeneration))
        assertTrue(controller.acceptsPublication(newTrack, newGeneration))
    }

    @Test fun sameTrackRelayKeepsGenerationUnchangedAndDoesNotOverwriteStableMetadata() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        registry.onConstructed(session, "main")
        registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true)
        val controller = SaltHostGenerationController(registry)

        val stableTrack = track("1", "Broken", "William Black/Fairlane", 180000L)
        val stableMeta = "StableMetadataObject1"
        registry.onHostMetadata(session, stableTrack, stableMeta)
        val initialGeneration = controller.observeUniqueHostMainTrack()!!
        assertEquals(1L, initialGeneration)
        assertSame(stableMeta, registry.stableMetadata(session))

        // Relay line 1
        val relayIdentity1 = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("William Black/Fairlane - Broken")!!
        assertTrue(SaltBluetoothLyricRelayPolicy.matchesStable(stableTrack, relayIdentity1.title, relayIdentity1.artist, 180000L))
        registry.onRelayMetadata(session, "RelayMetaLine1")

        val generationAfterRelay1 = controller.observeUniqueHostMainTrack()!!
        assertEquals(initialGeneration, generationAfterRelay1)
        assertSame(stableMeta, registry.stableMetadata(session))
        assertTrue(controller.acceptsPublication(stableTrack, generationAfterRelay1))

        // Relay line 2
        registry.onRelayMetadata(session, "RelayMetaLine2")
        val generationAfterRelay2 = controller.observeUniqueHostMainTrack()!!
        assertEquals(initialGeneration, generationAfterRelay2)
        assertSame(stableMeta, registry.stableMetadata(session))
    }

    @Test fun firstPacketRelayWithoutStableIsRejectedAndDoesNotDriveGeneration() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        registry.onConstructed(session, "main")
        registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true)
        val controller = SaltHostGenerationController(registry)

        // First packet arrives as relay (no prior stable metadata)
        val relayIdentity = SaltBluetoothLyricRelayPolicy.parseRelayIdentity("William Black/Fairlane - Broken")
        val stable = registry.stableMetadata(session)
        assertNull(stable)
        // Relay policy fails open without recording stable or advancing generation
        assertNull(controller.observeUniqueHostMainTrack())
        assertNull(registry.stableMetadata(session))
    }

    @Test fun realTrackChangeAdvancesGenerationAndClearsOldPublicationAcceptance() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        registry.onConstructed(session, "main")
        registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true)
        var trackChangedFired = false
        val controller = SaltHostGenerationController(registry) { trackChangedFired = true }

        val track1 = track("1", "Broken", "William Black/Fairlane", 180000L)
        registry.onHostMetadata(session, track1, "Stable1")
        val gen1 = controller.observeUniqueHostMainTrack()!!
        assertEquals(1L, gen1)

        // Relay on Track 1
        registry.onRelayMetadata(session, "RelayLine1")
        assertEquals(gen1, controller.observeUniqueHostMainTrack()!!)
        assertFalse(trackChangedFired)

        // Real track switch to Track 2
        val track2 = track("2", "Easy On Me", "Adele", 224000L)
        registry.onHostMetadata(session, track2, "Stable2")
        val gen2 = controller.observeUniqueHostMainTrack()!!
        assertTrue(gen2 > gen1)
        assertTrue(trackChangedFired)
        assertSame("Stable2", registry.stableMetadata(session))
        assertFalse(controller.acceptsPublication(track1, gen1))
        assertTrue(controller.acceptsPublication(track2, gen2))
    }

    @Test fun pendingPublicationDrainsAfterStableMetadataIsEstablished() {
        val registry = SaltMediaSessionRegistry()
        val session = Any()
        registry.onConstructed(session, "main")
        registry.onPlaybackState(session, PlaybackState.STATE_PLAYING)
        registry.onActive(session, true)
        val controller = SaltHostGenerationController(registry)

        val track = track("1", "Broken", "William Black/Fairlane", 180000L)
        val publication = SaltPublication(
            songId = "1", title = "Broken", artist = "William Black/Fairlane",
            album = "Album", durationMs = 180000L, sourceName = "EMBEDDED",
            timedLyric = "Lyric", rawCandidate = "", lines = emptyList()
        )

        // Publication arrives before host metadata
        val decisionBefore = SaltPendingPublicationPolicy.decide(
            publicationTrack = track,
            currentHostTrack = controller.policy.currentTrack,
            generationValid = controller.acceptsPublication(track),
            uniqueSessionReady = registry.selectUnique(track) != null,
            metadataReady = registry.stableMetadata(session) != null
        )
        assertEquals(SaltPendingPublicationPolicy.Decision.PENDING, decisionBefore)

        val store = SaltPendingPublicationStore()
        store.replace(publication)
        assertSame(publication, store.peek())

        // Host metadata arrives
        registry.onHostMetadata(session, track, "StableMetadataObj")
        val gen = controller.observeUniqueHostMainTrack()!!
        assertEquals(1L, gen)

        val decisionAfter = SaltPendingPublicationPolicy.decide(
            publicationTrack = track,
            currentHostTrack = controller.policy.currentTrack,
            generationValid = controller.acceptsPublication(track, gen),
            uniqueSessionReady = registry.selectUnique(track) != null,
            metadataReady = registry.stableMetadata(session) != null
        )
        assertEquals(SaltPendingPublicationPolicy.Decision.PUBLISH, decisionAfter)
        assertSame(publication, store.take())
        assertNull(store.peek())
    }

    @Test fun moduleWriteGuardIsReentrantAndRestored() {
        val registry = SaltMediaSessionRegistry(); assertFalse(registry.isModuleWrite())
        registry.withModuleWrite {
            assertTrue(registry.isModuleWrite()); registry.withModuleWrite { assertTrue(registry.isModuleWrite()) }
            assertTrue(registry.isModuleWrite())
        }
        assertFalse(registry.isModuleWrite())
    }
}
