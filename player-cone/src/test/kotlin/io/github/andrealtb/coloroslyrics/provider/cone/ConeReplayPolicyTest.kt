/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import java.lang.ref.WeakReference
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConeReplayPolicyTest {

    private val track = TrackIdentity(id = "1", title = "Title", artist = "Artist")
    private val sessionObj = Any()
    private val publication = ConePublication(ConeLyricSource.BROADCAST, "[00:01.00]Test", emptyList(), track)
    private val snapshot = ConeReplaySnapshot(WeakReference(sessionObj), track, 1L, publication)

    @Test
    fun isModuleOwned_identifiesConePayload() {
        val owned = """{"provider":"ink.trantor.coneplayer","source":"ink.trantor.coneplayer-v5","lines":[]}"""
        assertTrue(ConeReplayPolicy.isModuleOwned(owned))

        val foreign = """{"provider":"com.other.player","source":"other-v5"}"""
        assertFalse(ConeReplayPolicy.isModuleOwned(foreign))
        assertFalse(ConeReplayPolicy.isModuleOwned(null))
        assertFalse(ConeReplayPolicy.isModuleOwned(""))
    }

    @Test
    fun shouldReplay_acceptsValidSameTrackEmptyLyricInfo() {
        val result = ConeReplayPolicy.shouldReplay(
            cached = snapshot,
            selectedSession = sessionObj,
            incomingTrack = track,
            currentTrack = track,
            currentGeneration = 1L,
            generationValid = true,
            incomingLyricInfo = null
        )
        assertTrue(result)
    }

    @Test
    fun shouldReplay_rejectsWhenLyricInfoAlreadyPresentOrTrackMismatch() {
        assertFalse(
            ConeReplayPolicy.shouldReplay(
                cached = snapshot,
                selectedSession = sessionObj,
                incomingTrack = track,
                currentTrack = track,
                currentGeneration = 1L,
                generationValid = true,
                incomingLyricInfo = "existing_lyric_info"
            )
        )

        assertFalse(
            ConeReplayPolicy.shouldReplay(
                cached = snapshot,
                selectedSession = sessionObj,
                incomingTrack = TrackIdentity(id = "2", title = "Other", artist = "Other"),
                currentTrack = track,
                currentGeneration = 1L,
                generationValid = true,
                incomingLyricInfo = null
            )
        )
    }
}
