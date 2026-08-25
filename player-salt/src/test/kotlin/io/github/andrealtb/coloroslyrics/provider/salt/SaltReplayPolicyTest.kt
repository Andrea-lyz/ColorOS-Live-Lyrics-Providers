package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import java.lang.ref.WeakReference
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaltReplayPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")
    private val session = Any()
    private val publication = SaltPublication("1", "Title", "Artist", "", 0, "EMBEDDED", "", "", emptyList())
    private val snapshot = SaltReplaySnapshot(WeakReference(session), track, 3, publication)

    @Test fun replaysOnlyMissingPayloadForSameSessionTrackAndGeneration() {
        assertTrue(SaltReplayPolicy.shouldReplay(snapshot, session, track, track, 3, true, null))
        assertFalse(SaltReplayPolicy.shouldReplay(snapshot, Any(), track, track, 3, true, null))
        assertFalse(SaltReplayPolicy.shouldReplay(snapshot, session, track.copy(id = "2"), track, 3, true, null))
        assertFalse(SaltReplayPolicy.shouldReplay(snapshot, session, track, track, 4, false, null))
        assertFalse(SaltReplayPolicy.shouldReplay(snapshot, session, track, track, 3, true, "foreign"))
    }

    @Test fun ownershipRequiresProviderAndSourceMarkers() {
        val owned = "{${SaltReplayPolicy.OWNED_PROVIDER_FRAGMENT},${SaltReplayPolicy.OWNED_SOURCE_FRAGMENT}}"
        assertTrue(SaltReplayPolicy.isModuleOwned(owned))
        assertFalse(SaltReplayPolicy.isModuleOwned("{\"provider\":\"other\"}"))
    }
}
