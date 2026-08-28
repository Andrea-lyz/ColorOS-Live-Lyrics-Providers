/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConeLyricCandidatePolicyTest {

    private val track1 = TrackIdentity(id = "1", title = "Song A", artist = "Artist A")
    private val track2 = TrackIdentity(id = "2", title = "Song B", artist = "Artist B")
    private val validLrc1 = "[00:01.00]Lyric for Song A\n[00:05.00]Second line"
    private val validLrc2 = "[00:02.00]Better Lyric for Song A\n[00:06.00]Second line"
    private val placeholderLrc = "[00:00.00]纯音乐，请欣赏"

    @Test
    fun evaluate_withPlaceholder_returnsNull() {
        val policy = ConeLyricCandidatePolicy()
        val result = policy.evaluate(ConeLyricSource.BROADCAST, placeholderLrc, track1)
        assertNull(result)
    }

    @Test
    fun evaluate_broadcastCandidate_isAccepted() {
        val policy = ConeLyricCandidatePolicy()
        val result = policy.evaluate(ConeLyricSource.BROADCAST, validLrc1, track1)
        assertNotNull(result)
        assertEquals(ConeLyricSource.BROADCAST, result.source)
        assertEquals(validLrc1, result.rawLyric)
        assertEquals(2, result.lines.size)
    }

    @Test
    fun evaluate_priorityOrdering_higherPriorityReplacesLower() {
        val policy = ConeLyricCandidatePolicy()

        // 1. Lower priority (TRACK_METADATA: 60) arrives first
        val metaCandidate = policy.evaluate(ConeLyricSource.TRACK_METADATA, validLrc1, track1)
        assertNotNull(metaCandidate)
        assertEquals(ConeLyricSource.TRACK_METADATA, policy.peekBest()?.source)

        // 2. Higher priority (BROADCAST: 100) arrives second
        val broadcastCandidate = policy.evaluate(ConeLyricSource.BROADCAST, validLrc2, track1)
        assertNotNull(broadcastCandidate)
        assertEquals(ConeLyricSource.BROADCAST, policy.peekBest()?.source)
        assertEquals(validLrc2, policy.peekBest()?.rawLyric)

        // 3. Lower priority (PARSER: 80) arrives third -> rejected
        val parserCandidate = policy.evaluate(ConeLyricSource.PARSER, validLrc1, track1)
        assertNull(parserCandidate)
        assertEquals(ConeLyricSource.BROADCAST, policy.peekBest()?.source)
    }

    @Test
    fun evaluate_duplicateContent_isRejected() {
        val policy = ConeLyricCandidatePolicy()
        val first = policy.evaluate(ConeLyricSource.BROADCAST, validLrc1, track1)
        assertNotNull(first)

        val second = policy.evaluate(ConeLyricSource.BROADCAST, validLrc1, track1)
        assertNull(second, "Duplicate candidate should be rejected")
    }

    @Test
    fun onTrackChanged_resetsCandidateState() {
        val policy = ConeLyricCandidatePolicy()
        policy.evaluate(ConeLyricSource.BROADCAST, validLrc1, track1)
        assertEquals(ConeLyricSource.BROADCAST, policy.peekBest()?.source)

        policy.onTrackChanged(track2)
        assertNull(policy.peekBest())

        // Now track2 can accept track_metadata
        val result = policy.evaluate(ConeLyricSource.TRACK_METADATA, validLrc1, track2)
        assertNotNull(result)
        assertEquals(ConeLyricSource.TRACK_METADATA, policy.peekBest()?.source)
    }
}
