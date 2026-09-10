/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.universal.session

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrackIdentityResolverTest {
    private val resolver = TrackIdentityResolver()

    @Test
    fun lyricLineChurnDoesNotCreateNewTrack() {
        val first = resolver.observe(saltLine("We're a wreck, you're the wreckin' ball\n我们破镜难圆 你是罪魁祸首"))
        val second = resolver.observe(saltLine("Second lyric line"))
        assertEquals(1L, first.descriptor.trackGeneration)
        assertEquals(1L, second.descriptor.trackGeneration)
        assertEquals("Babe (Taylor's Version) (From The Vault)", second.descriptor.title)
        assertEquals("Taylor Swift", second.descriptor.artist)
        assertTrue(second.descriptor.titlePolluted)
        assertNotEquals(first.descriptor.metadataRevision, second.descriptor.metadataRevision)
    }

    @Test
    fun pollutedSongSwitchWithSameArtistIncrementsGeneration() {
        val song1 = resolver.observe(
            observation(
                title = "Lyric line for Song 1",
                artist = "Taylor Swift - Song One",
                albumArtist = "Taylor Swift",
                album = "Same Album",
                durationMs = 200000L
            )
        )
        assertEquals(1L, song1.descriptor.trackGeneration)
        assertEquals("Song One", song1.descriptor.title)

        val song2 = resolver.observe(
            observation(
                title = "Lyric line for Song 2",
                artist = "Taylor Swift - Song Two",
                albumArtist = "Taylor Swift",
                album = "Same Album",
                durationMs = 210000L
            )
        )
        assertEquals(2L, song2.descriptor.trackGeneration)
        assertEquals("Song Two", song2.descriptor.title)
    }

    @Test
    fun pollutedToCleanMetadataKeepsGenerationAcrossAlbumTransient() {
        val polluted = resolver.observe(
            observation(
                title = "Current lyric line",
                artist = "Taylor Swift - Enchanted (Taylor's Version)",
                albumArtist = "Taylor Swift",
                album = "Speak Now (Taylor's Version)",
                durationMs = 231000L
            )
        )
        val clean = resolver.observe(
            observation(
                title = "Enchanted (Taylor's Version)",
                artist = "Taylor Swift",
                album = null,
                durationMs = 231000L
            )
        )
        assertTrue(polluted.descriptor.titlePolluted)
        assertEquals(1L, clean.descriptor.trackGeneration)
        assertEquals("Enchanted (Taylor's Version)", clean.descriptor.title)
    }

    @Test
    fun realTrackChangeIncrementsGeneration() {
        resolver.observe(saltLine("line-1"))
        val next = resolver.observe(
            observation(
                title = "All I Ask",
                artist = "Adele",
                album = "25",
                durationMs = 271000L
            )
        )
        assertEquals(2L, next.descriptor.trackGeneration)
        assertEquals("All I Ask", next.descriptor.title)
        assertFalse(next.descriptor.titlePolluted)
    }

    @Test
    fun independentSessionsKeepSeparateGenerations() {
        val salt = resolver.observe(saltLine("line-1"))
        val other = resolver.observe(
            observation(
                sessionInstanceId = "other",
                ownerPackage = "com.example.other",
                title = "Song B",
                artist = "Artist B",
                durationMs = 180000L
            )
        )
        assertEquals(1L, salt.descriptor.trackGeneration)
        assertEquals(1L, other.descriptor.trackGeneration)
        assertEquals("salt", salt.descriptor.sessionInstanceId)
        assertEquals("other", other.descriptor.sessionInstanceId)
    }

    private fun saltLine(title: String): SessionObservation = observation(
        title = title,
        artist = "Taylor Swift - Babe (Taylor's Version) (From The Vault)",
        albumArtist = "Taylor Swift",
        album = "Red (Taylor's Version) [24_96]",
        durationMs = 224240L
    )

    private fun observation(
        sessionInstanceId: String = "salt",
        ownerPackage: String = "com.salt.music",
        title: String,
        artist: String,
        albumArtist: String? = null,
        album: String? = null,
        durationMs: Long? = null,
        playing: Boolean = true
    ): SessionObservation = SessionObservation(
        userId = 0,
        ownerPackage = ownerPackage,
        sessionInstanceId = sessionInstanceId,
        raw = RawSessionMetadata(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            durationMs = durationMs
        ),
        playbackState = if (playing) PlaybackStates.PLAYING else PlaybackStates.PAUSED,
        playing = playing,
        observedAtElapsedMs = 1_000L
    )
}
