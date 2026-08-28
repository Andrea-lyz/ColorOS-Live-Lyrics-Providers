/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouLyricInfoPublisherTest {

    private val track = TrackIdentity(
        id = "7F605167C85BB66AA6E0388144897547",
        title = "Night",
        artist = "Singer"
    )

    @Test
    fun matchesByOfficialSongIdEvenWhenTitlesDiverge() {
        assertTrue(
            KuGouLyricInfoPublisher.matchesPublication(
                hostPackage = KuGouPlayerConstants.STANDARD_PACKAGE,
                title = "unrelated display",
                artist = "other",
                songId = "7F605167C85BB66AA6E0388144897547",
                track = track
            )
        )
    }

    @Test
    fun liteCarLyricDisplayStillMatchesTheBoundTrack() {
        assertTrue(
            KuGouLyricInfoPublisher.matchesPublication(
                hostPackage = KuGouPlayerConstants.LITE_PACKAGE,
                title = "I couldn't wait for you to come clear the cupboards - extra",
                artist = "Singer - Night",
                songId = null,
                track = track
            )
        )
        assertFalse(
            KuGouLyricInfoPublisher.matchesPublication(
                hostPackage = KuGouPlayerConstants.STANDARD_PACKAGE,
                title = "I couldn't wait for you to come clear the cupboards - extra",
                artist = "Singer - Night",
                songId = null,
                track = track
            )
        )
    }

    @Test
    fun liteLongWesternTitleMatchesWithoutRewritingToLastArtistToken() {
        val swift = TrackIdentity(
            id = "c0874801bdc82b969d9dde5f313e14b4",
            title = "I Knew It, I Knew You",
            artist = "Taylor Swift"
        )
        assertTrue(
            KuGouLyricInfoPublisher.matchesPublication(
                hostPackage = KuGouPlayerConstants.LITE_PACKAGE,
                title = "I Knew It, I Knew You",
                artist = "Taylor Swift",
                songId = null,
                track = swift
            )
        )
    }
}
