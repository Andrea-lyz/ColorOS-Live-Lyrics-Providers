/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import java.lang.ref.WeakReference
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetrolistReplayPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")
    private val sessionObj = Any()
    private val publication = MetrolistPublication("[00:01.00]Test", "", emptyList(), track)
    private val snapshot = MetrolistReplaySnapshot(WeakReference(sessionObj), track, 1L, publication)
    private val hostPackage = MetrolistPlayerConstants.HOST_PACKAGE

    @Test
    fun ownershipRequiresHostProviderAndV5Source() {
        val owned =
            "{\"provider\":\"$hostPackage\",\"source\":\"$hostPackage-v5\",\"lyric\":\"[00:01.000]Hi\"}"
        assertTrue(MetrolistReplayPolicy.isModuleOwned(owned, hostPackage))
        assertFalse(
            MetrolistReplayPolicy.isModuleOwned(
                "{\"provider\":\"com.other.player\",\"source\":\"com.other.player-v5\"}",
                hostPackage
            )
        )
        assertFalse(MetrolistReplayPolicy.isModuleOwned(null, hostPackage))
    }

    @Test
    fun replaysOnlyMissingPayloadForSameSessionTrackAndReadyArtwork() {
        assertTrue(
            MetrolistReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, null
            )
        )
        assertFalse(
            MetrolistReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, false, null
            )
        )
        assertFalse(
            MetrolistReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, "existing"
            )
        )
        assertFalse(
            MetrolistReplayPolicy.shouldReplay(
                snapshot, Any(), track, track, 1L, true, true, null
            )
        )
    }
}

