/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import org.junit.Assert.assertEquals
import org.junit.Test

class QqSongInfoReaderTest {

    @Test
    fun prefersCurrentOfficialGettersOverLegacyNames() {
        val songInfo = FakeSongInfo()
        val track = QqSongInfoReader.read(songInfo)
        assertEquals("99", track.id)
        assertEquals("Title", track.title)
        assertEquals("Artist", track.artist)
    }

    @Test
    fun readsLoadBeanGettersWithoutUsingRoma() {
        val bean = FakeLoadBean()
        val (track, models) = QqSongInfoReader.readFromLoadBean(bean)
        assertEquals("99", track.id)
        assertEquals("main", models.primary)
        assertEquals("trans", models.translation)
    }

    class FakeSongInfo {
        fun H2(): Long = 99L
        fun j3(): String = "Title"
        fun V3(): String = "Artist"
        fun X2(): String = "legacy-title"
        fun C3(): String = "legacy-artist"
        fun v2(): String = "legacy-id"
    }

    class FakeLoadBean {
        fun f(): FakeSongInfo = FakeSongInfo()
        fun c(): String = "main"
        fun h(): String = "trans"
        fun e(): String = "roma"
    }
}
