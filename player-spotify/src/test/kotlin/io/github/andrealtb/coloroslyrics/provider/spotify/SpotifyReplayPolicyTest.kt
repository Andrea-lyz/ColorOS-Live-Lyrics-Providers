/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import java.lang.ref.WeakReference
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyReplayPolicyTest {
    private val track = TrackIdentity(
        id = "spotify:track:1",
        title = "Title",
        artist = "Artist"
    )
    private val sessionObj = Any()
    private val publication = SpotifyPublication("", "", emptyList(), track)
    private val snapshot = SpotifyReplaySnapshot(WeakReference(sessionObj), track, 1L, publication)
    private val hostPackage = SpotifyPlayerConstants.HOST_PACKAGE

    @Test
    fun ownershipRequiresHostProviderAndV5Source() {
        val owned =
            "{\"provider\":\"$hostPackage\",\"source\":\"$hostPackage-v5\",\"lyric\":\"[00:01.000]Hi\"}"
        assertTrue(SpotifyReplayPolicy.isModuleOwned(owned, hostPackage))
        assertFalse(
            SpotifyReplayPolicy.isModuleOwned(
                "{\"provider\":\"com.other.player\",\"source\":\"com.other.player-v5\"}",
                hostPackage
            )
        )
        assertFalse(SpotifyReplayPolicy.isModuleOwned(null, hostPackage))
    }

    @Test
    fun replaysOnlyMissingPayloadForSameSessionTrack() {
        assertTrue(
            SpotifyReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, null
            )
        )
        assertFalse(
            SpotifyReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, "existing"
            )
        )
        assertFalse(
            SpotifyReplayPolicy.shouldReplay(
                snapshot, Any(), track, track, 1L, true, true, null
            )
        )
    }

    @Test
    fun stripsStaleModulePayloadOnAdsAndTrackMismatch() {
        val owned =
            "{\"provider\":\"$hostPackage\",\"source\":\"$hostPackage-v5\",\"lyric\":\"[00:01.000]Hi\"}"
        assertTrue(
            SpotifyReplayPolicy.shouldStripStale(owned, hostPackage, null, track)
        )
        assertTrue(
            SpotifyReplayPolicy.shouldStripStale(
                owned,
                hostPackage,
                track.copy(id = "spotify:track:2"),
                track
            )
        )
        assertFalse(
            SpotifyReplayPolicy.shouldStripStale(owned, hostPackage, track, track)
        )
        assertFalse(
            SpotifyReplayPolicy.shouldStripStale("official", hostPackage, null, track)
        )
    }
}
