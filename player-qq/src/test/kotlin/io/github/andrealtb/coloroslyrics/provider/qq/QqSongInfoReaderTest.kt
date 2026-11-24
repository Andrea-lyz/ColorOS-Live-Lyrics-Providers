/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun readsQq21SongInfoAndSkipsShiftedLegacyNames() {
        val track = QqSongInfoReader.read(FakeSongInfo21())
        assertEquals("99", track.id)
        assertEquals("Title", track.title)
        assertEquals("Artist", track.artist)
    }

    @Test
    fun readsLoadBeanFieldsWithoutUsingRoma() {
        val (track, models) = QqSongInfoReader.readFromLoadBean(FakeLoadBean())
        assertEquals("99", track.id)
        assertEquals("main", models.primary)
        assertEquals("trans", models.translation)
    }

    @Test
    fun qq21ShiftedLoadBeanGettersDoNotHijackTranslation() {
        val (track, models) = QqSongInfoReader.readFromLoadBean(FakeLoadBean21())
        assertEquals("99", track.id)
        assertEquals("Title", track.title)
        assertEquals("main", models.primary)
        assertEquals("trans", models.translation)
    }

    @Test
    fun readsUngatedTranslationFromLoadModelSuperclassFields() {
        val (track, translation) = QqSongInfoReader.readFromLoadModel(FakePlayingLoadModel())
        assertEquals("99", track.id)
        assertEquals("trans", translation)
    }

    @Test
    fun loadModelWithoutTranslationReturnsNull() {
        val (_, translation) = QqSongInfoReader.readFromLoadModel(FakeLoadModel(trans = null))
        assertNull(translation)
    }

    @Test
    fun numericLookingTitlesAreNotDiscardedAsPlaceholders() {
        val track = QqSongInfoReader.read(NumericTitleSongInfo())
        assertEquals("1", track.title)
        assertEquals("0", track.artist)
    }

    class FakeSongInfo {
        fun H2(): Long = 99L
        fun j3(): String = "Title"
        fun V3(): String = "Artist"
        fun X2(): String = "legacy-title"
        fun C3(): String = "legacy-artist"
        fun v2(): String = "legacy-id"
    }

    /** 21.0.0.8 shape: legacy names now return other types. */
    class FakeSongInfo21 {
        fun I2(): Long = 99L
        fun H2(): Any = Any()
        fun Y3(): String = "Title"
        fun j3(): Int = 7
        fun Z3(): String = "Artist"
        fun V3(): String = "not-artist"
    }

    /** 20.7.5.8 LyricLoadBean: fields a/b/c/d with getters f/c/h/e. */
    @Suppress("unused")
    class FakeLoadBean {
        private val a: Any = FakeSongInfo()
        private val b: Any = "main"
        private val c: Any = "trans"
        private val d: Any = "roma"
        fun f(): Any = a
        fun c(): Any = b
        fun h(): Any = c
        fun e(): Any = d
    }

    /** 21.0.0.8 LyricLoadBean: same fields, getters shifted (h() = SongInfo). */
    @Suppress("unused")
    class FakeLoadBean21 {
        private val a: Any = FakeSongInfo21()
        private val b: Any = "main"
        private val c: Any = "trans"
        private val d: Any = "roma"
        private val h: Any = "multi-style"
        fun e(): Any = b
        fun f(): Any = h
        fun g(): Any = d
        fun h(): Any = a
        fun j(): Any = c
    }

    /** `model.a` layout (20.7.5.8 and 21.0.0.8): c songInfo, g lyric, h transLyric. */
    @Suppress("unused")
    open class FakeLoadModel(trans: Any? = "trans") {
        @JvmField val c: Any = FakeSongInfo21()
        @JvmField val g: Any = "main"
        @JvmField val h: Any? = trans
    }

    /** QQ notifies a subclass (the playing-lyric loader); fields live on the base. */
    class FakePlayingLoadModel : FakeLoadModel()

    class NumericTitleSongInfo {
        fun H2(): Long = 99L
        fun j3(): String = "1"
        fun V3(): String = "0"
    }
}
