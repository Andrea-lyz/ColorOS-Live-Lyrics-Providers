/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import java.lang.ref.WeakReference
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LxReplayPolicyTest {
    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")
    private val sessionObj = Any()
    private val publication = LxPublication("[00:01.00]Test", "", emptyList(), track)
    private val snapshot = LxReplaySnapshot(WeakReference(sessionObj), track, 1L, publication)
    private val hostPackage = LxPlayerConstants.LX_OFFICIAL_PACKAGE

    @Test
    fun ownershipRequiresHostProviderAndV5Source() {
        val owned =
            """{"provider":"$hostPackage","source":"$hostPackage-v5","lyric":"[00:01.000]Hi"}"""
        assertTrue(LxReplayPolicy.isModuleOwned(owned, hostPackage))
        assertFalse(
            LxReplayPolicy.isModuleOwned(
                """{"provider":"com.other.player","source":"com.other.player-v5"}""",
                hostPackage
            )
        )
        assertFalse(LxReplayPolicy.isModuleOwned(owned, LxPlayerConstants.LX_WALNUT_PACKAGE))
        assertFalse(LxReplayPolicy.isModuleOwned(null, hostPackage))
    }

    @Test
    fun replaysOnlyMissingPayloadForSameSessionTrackAndGeneration() {
        assertTrue(
            LxReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, null
            )
        )
        assertFalse(
            LxReplayPolicy.shouldReplay(
                snapshot, Any(), track, track, 1L, true, true, null
            )
        )
        assertTrue(
            LxReplayPolicy.shouldReplay(
                snapshot, sessionObj, track.copy(id = "2"), track, 1L, true, true, null
            )
        )
        assertFalse(
            LxReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, "existing"
            )
        )
        assertTrue(
            LxReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, true, ""
            )
        )
        assertFalse(
            LxReplayPolicy.shouldReplay(
                snapshot,
                sessionObj,
                TrackIdentity(title = "Other", artist = "Artist"),
                track,
                1L,
                true,
                true,
                null
            )
        )
        assertFalse(
            LxReplayPolicy.shouldReplay(
                snapshot, sessionObj, track, track, 1L, true, false, null
            )
        )
    }
}
