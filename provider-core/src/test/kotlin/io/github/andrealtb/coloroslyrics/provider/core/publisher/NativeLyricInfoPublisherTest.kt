/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.publisher

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeLyricInfoPublisherTest {

    private val track = TrackIdentity(id = "track-1", title = "Song", artist = "Artist")
    private val lines = listOf(
        RichLyricLine(begin = 1_000L, end = 2_000L, duration = 1_000L, text = "Line")
    )

    @Test
    fun commitsOnlyAfterCandidatePassesParcelGate() {
        val fixture = Fixture(parcelBytes = 4096)
        val result = publish(fixture)

        assertEquals(NativeLyricInfoPublisher.Result.PUBLISHED, result)
        assertTrue(fixture.committed)
        assertEquals("original", fixture.candidate?.get("title"))
        assertTrue(fixture.candidate?.containsKey("lyricInfo") == true)
    }

    @Test
    fun oversizedCandidateLeavesTargetBuilderUntouched() {
        val fixture = Fixture(parcelBytes = NativeLyricInfoPublisher.MAX_PARCEL_BYTES + 1)
        val result = publish(fixture)

        assertEquals(NativeLyricInfoPublisher.Result.PAYLOAD_TOO_LARGE, result)
        assertFalse(fixture.committed)
        assertFalse(fixture.builder.containsKey("lyricInfo"))
    }

    @Test
    fun measurementFailureLeavesTargetBuilderUntouched() {
        val fixture = Fixture(parcelBytes = null)
        val result = publish(fixture)

        assertEquals(NativeLyricInfoPublisher.Result.PARCEL_MEASUREMENT_FAILED, result)
        assertFalse(fixture.committed)
    }

    @Test
    fun commitFailureDoesNotEscapeIntoTheHost() {
        val fixture = Fixture(parcelBytes = 100, failCommit = true)
        val result = publish(fixture)

        assertEquals(NativeLyricInfoPublisher.Result.COMMIT_FAILED, result)
        assertFalse(fixture.committed)
    }

    @Test
    fun rejectsWrongHostAndStaleGenerationBeforeMutation() {
        val wrongHost = Fixture(parcelBytes = 100)
        val policy = TrackGenerationPolicy()
        val generation = policy.onTrackObserved(track)
        val wrongHostResult = NativeLyricInfoPublisher.publishTransactional(
            wrongHost.builder,
            wrongHost.original,
            track,
            lines,
            generation,
            policy,
            "com.test.player",
            "com.other.player",
            wrongHost
        )
        assertEquals(NativeLyricInfoPublisher.Result.HOST_PACKAGE_MISMATCH, wrongHostResult)
        assertFalse(wrongHost.committed)

        val stale = Fixture(parcelBytes = 100)
        policy.onTrackObserved(track.copy(id = "track-2"))
        val staleResult = NativeLyricInfoPublisher.publishTransactional(
            stale.builder,
            stale.original,
            track,
            lines,
            generation,
            policy,
            "com.test.player",
            "com.test.player",
            stale
        )
        assertEquals(NativeLyricInfoPublisher.Result.STALE_GENERATION, staleResult)
        assertFalse(stale.committed)
    }

    private fun publish(fixture: Fixture): NativeLyricInfoPublisher.Result {
        val policy = TrackGenerationPolicy()
        val generation = policy.onTrackObserved(track)
        return NativeLyricInfoPublisher.publishTransactional(
            fixture.builder,
            fixture.original,
            track,
            lines,
            generation,
            policy,
            "com.test.player",
            "com.test.player",
            fixture
        )
    }

    private class Fixture(
        private val parcelBytes: Int?,
        private val failCommit: Boolean = false
    ) : NativeLyricInfoPublisher.MetadataTransaction<MutableMap<String, String>, MutableMap<String, String>> {
        val original = mutableMapOf("title" to "original")
        val builder = original.toMutableMap()
        var candidate: MutableMap<String, String>? = null
        var committed = false

        override fun buildCandidate(
            originalMetadata: MutableMap<String, String>?,
            key: String,
            value: String
        ): MutableMap<String, String> = originalMetadata.orEmpty().toMutableMap().apply {
            put(key, value)
            candidate = this
        }

        override fun measureParcelBytes(metadata: MutableMap<String, String>): Int? = parcelBytes

        override fun commit(builder: MutableMap<String, String>, key: String, value: String) {
            if (failCommit) error("host builder rejected mutation")
            committed = true
            builder[key] = value
        }
    }
}
