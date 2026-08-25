/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaltLyricDecoderTest {

    @Test
    fun decodesSongIdentityAndDuration() {
        val song = SaltSong("song-1", "Title", "Artist", "Album", 180_000L)
        val publication = SaltLyricDecoder.decodeSong(
            song,
            SaltSong::class.java,
            SaltSong::class.java.getMethod("getAlbum"),
            SaltSong::class.java.getMethod("getDuration")
        )
        assertEquals("song-1", publication?.songId)
        assertEquals("Title", publication?.title)
        assertEquals("Artist", publication?.artist)
        assertEquals("Album", publication?.album)
        assertEquals(180_000L, publication?.durationMs)
    }

    @Test
    fun decodesSourceTimedAndRawCandidates() {
        val result = SaltLyricResultFixture(
            raw = "[00:01.00]first\n[00:02.00]second",
            charset = "UTF-8",
            source = SaltSourceFixture.EMBEDDED,
            canScroll = SaltScrollFixture.CAN_SCROLL
        )
        val decoded = SaltLyricDecoder.decodeResult(result, SaltSourceFixture::class.java)
        assertEquals("EMBEDDED", decoded.sourceName)
        assertTrue(decoded.timedLyric.contains("[00:01.00]"))
        assertTrue(decoded.lines.isNotEmpty())
        assertEquals("first", decoded.lines.first().text)
    }

    @Test
    fun emptyResultClassifiesWithoutCrashing() {
        val result = SaltLyricResultFixture(
            raw = "",
            charset = "UTF-8",
            source = SaltSourceFixture.NOT_FOUND,
            canScroll = SaltScrollFixture.NOT_SCROLL
        )
        val decoded = SaltLyricDecoder.decodeResult(result, SaltSourceFixture::class.java)
        assertEquals("NOT_FOUND", decoded.sourceName)
        assertEquals("", decoded.timedLyric)
        assertTrue(decoded.lines.isEmpty())
    }

    @Test
    fun findsSongAndResultFieldsFromPublisher() {
        val song = SaltSong("song-2", "Title B", "Artist B", "Album B", 1000L)
        val result = SaltLyricResultFixture(
            raw = "[00:01.00]line",
            charset = "UTF-8",
            source = SaltSourceFixture.EMBEDDED,
            canScroll = SaltScrollFixture.CAN_SCROLL
        )
        val publisher = SaltPublisherFixture(song, result)
        assertEquals(song, SaltLyricDecoder.findFieldValueOfType(publisher, SaltSong::class.java))
        assertEquals(
            result,
            SaltLyricDecoder.findFieldValueOfType(publisher, SaltLyricResultFixture::class.java)
        )
    }

    @Test
    fun sameNameDifferentIdIdentityIsPreserved() {
        val song = SaltSong("song-x", "Same Title", "Same Artist", "Album", 1000L)
        val publication = SaltLyricDecoder.decodeSong(
            song,
            SaltSong::class.java,
            SaltSong::class.java.getMethod("getAlbum"),
            SaltSong::class.java.getMethod("getDuration")
        )
        assertEquals("song-x", publication?.songId)
    }

    class SaltSong(
        private val id: String,
        private val title: String,
        private val artist: String,
        private val album: String,
        private val duration: Long
    ) {
        fun getId(): String = id
        fun getTitle(): String = title
        fun getArtist(): String = artist
        fun getAlbum(): String = album
        fun getDuration(): Long = duration
    }

    class SaltLyricResultFixture(
        private val raw: String,
        private val charset: String,
        private val source: SaltSourceFixture,
        private val canScroll: SaltScrollFixture
    )

    class SaltPublisherFixture(
        private val song: SaltSong,
        private val result: SaltLyricResultFixture
    )

    enum class SaltSourceFixture {
        UNKNOWN,
        EMBEDDED,
        TAG_LYRICS3_V2,
        NOT_FOUND
    }

    enum class SaltScrollFixture {
        CAN_SCROLL,
        NOT_SCROLL,
        NONE
    }
}
