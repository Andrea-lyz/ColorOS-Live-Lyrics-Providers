package io.github.andrealtb.coloroslyrics.provider.salt

import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SaltMediaSessionRegistryTest {
    private fun track(id: String) = TrackIdentity(id = id, title = "Same", artist = "Artist")

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

    @Test fun moduleWriteGuardIsReentrantAndRestored() {
        val registry = SaltMediaSessionRegistry(); assertFalse(registry.isModuleWrite())
        registry.withModuleWrite {
            assertTrue(registry.isModuleWrite()); registry.withModuleWrite { assertTrue(registry.isModuleWrite()) }
            assertTrue(registry.isModuleWrite())
        }
        assertFalse(registry.isModuleWrite())
    }
}
