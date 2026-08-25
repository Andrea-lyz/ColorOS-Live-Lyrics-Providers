package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SaltPendingPublicationPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")

    @Test fun missingHostTrackOrSessionReadinessProducesPending() {
        assertEquals(SaltPendingPublicationPolicy.Decision.PENDING,
            SaltPendingPublicationPolicy.decide(track, null, false, false, false))
        assertEquals(SaltPendingPublicationPolicy.Decision.PENDING,
            SaltPendingPublicationPolicy.decide(track, track, true, false, false))
        assertEquals(SaltPendingPublicationPolicy.Decision.PENDING,
            SaltPendingPublicationPolicy.decide(track, track, true, true, false))
    }

    @Test fun explicitDifferentTrackIsNeverPending() {
        assertEquals(SaltPendingPublicationPolicy.Decision.DROP_STALE,
            SaltPendingPublicationPolicy.decide(track, track.copy(id = "2"), true, false, false))
    }

    @Test fun readySameTrackPublishes() {
        assertEquals(SaltPendingPublicationPolicy.Decision.PUBLISH,
            SaltPendingPublicationPolicy.decide(track, track, true, true, true))
    }

    @Test fun storeContainsAtMostOneAndReplacementReturnsOld() {
        val store = SaltPendingPublicationStore()
        val first = publication("1")
        val second = publication("2")
        assertNull(store.replace(first))
        assertSame(first, store.replace(second))
        assertSame(second, store.take())
        assertNull(store.peek())
    }

    @Test fun takeIfSameDoesNotConsumeReplacementPublication() {
        val store = SaltPendingPublicationStore()
        val first = publication("1")
        val second = publication("2")
        store.replace(first)
        store.replace(second)

        assertFalse(store.takeIfSame(first))
        assertSame(second, store.peek())
        assertTrue(store.takeIfSame(second))
        assertNull(store.peek())
    }

    private fun publication(id: String) = SaltPublication(
        id, "Title", "Artist", "", 0L, "EMBEDDED", "", "", emptyList()
    )
}
