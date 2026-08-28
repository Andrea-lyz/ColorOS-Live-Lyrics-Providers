/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricInfoReaderTest {

    @Test
    fun readsUnobfuscatedLyricDataAndRejectsMismatchedIds() {
        val music = FakeMusicInfo(
            filterMusicId = 314159L,
            musicName = "夜曲",
            singerName = "周杰伦",
            albumName = "十一月的萧邦"
        )
        val lyric = FakeLyricInfo(
            musicId = 314159L,
            rawData = FakeLyricData(
                lrc = "[00:01.00]夜曲",
                yrc = "[1000,500](1000,200,0)夜(1200,300,0)曲",
                lrcTranslateLyric = "[00:01.00]Nocturne",
                yrcTranslateLyric = "",
                lrcRomeLyric = "ye qu"
            )
        )
        val snapshot = NeteaseLyricInfoReader.read(lyric, music)
        assertTrue(snapshot.idsMatch)
        assertEquals("314159", snapshot.track.id)
        assertEquals("夜曲", snapshot.track.title)
        assertEquals("周杰伦", snapshot.track.artist)
        assertEquals("十一月的萧邦", snapshot.track.album)
        assertEquals("[1000,500](1000,200,0)夜(1200,300,0)曲", snapshot.yrc)
        assertEquals("[00:01.00]Nocturne", snapshot.lrcTranslate)
        assertNull(NeteaseLyricInfoReader.invokeNoArg(lyric.getRawData(), listOf("getLrcRomeLyric")))
    }

    @Test
    fun mismatchedFilterAndLyricIdsAreNotOfficialMatches() {
        val snapshot = NeteaseLyricInfoReader.read(
            FakeLyricInfo(musicId = 1L, rawData = FakeLyricData()),
            FakeMusicInfo(filterMusicId = 2L)
        )
        assertFalse(snapshot.idsMatch)
    }

    @Test
    fun findsMusicInfoFieldMatchingLyricId() {
        val match = FakeMusicInfo(filterMusicId = 7L, musicName = "夜曲")
        val other = FakeMusicInfo(filterMusicId = 8L, musicName = "其他")
        val holder = FakeHandler(current = match, previous = other)
        val found = NeteaseLyricInfoReader.findMusicInfo(holder, "7")
        assertEquals(match, found)
        assertTrue(NeteaseLyricInfoReader.looksLikeLyricInfo(FakeLyricInfo(musicId = 7L)))
        assertFalse(NeteaseLyricInfoReader.looksLikeLyricInfoClass(FakeLyricInfo(musicId = 7L)))
        assertFalse(NeteaseLyricInfoReader.looksLikeLyricInfo("not-lyric"))
        val lyric = FakeLyricInfo(musicId = 7L)
        assertEquals(
            lyric,
            NeteaseLyricInfoReader.firstLyricInfoArg(arrayOf("skip", lyric, match))
        )
    }

    @Test
    fun getIdAloneIsNotMusicInfo() {
        val holder = FakeIdOnlyHolder(FakeIdOnly())
        assertEquals(null, NeteaseLyricInfoReader.findMusicInfo(holder, "1"))
    }

    class FakeHandler(
        @JvmField val current: FakeMusicInfo,
        @JvmField val previous: FakeMusicInfo
    )

    class FakeMusicInfo(
        private val filterMusicId: Long = 0L,
        private val musicName: String? = null,
        private val singerName: String? = null,
        private val albumName: String? = null
    ) {
        fun getFilterMusicId(): Long = filterMusicId
        fun getMusicName(): String? = musicName
        fun getSingerName(): String? = singerName
        fun getAlbumName(): String? = albumName
    }

    class FakeLyricInfo(
        private val musicId: Long = 0L,
        private val rawData: FakeLyricData = FakeLyricData()
    ) {
        fun getMusicId(): Long = musicId
        fun getRawData(): FakeLyricData = rawData
    }

    class FakeIdOnlyHolder(@JvmField val value: FakeIdOnly)

    class FakeIdOnly {
        fun getId(): Long = 1L
    }

    class FakeLyricData(
        private val lrc: String? = null,
        private val yrc: String? = null,
        private val lrcTranslateLyric: String? = null,
        private val yrcTranslateLyric: String? = null,
        private val lrcRomeLyric: String? = null
    ) {
        fun getLrc(): String? = lrc
        fun getYrc(): String? = yrc
        fun getLrcTranslateLyric(): String? = lrcTranslateLyric
        fun getYrcTranslateLyric(): String? = yrcTranslateLyric
        fun getLrcRomeLyric(): String? = lrcRomeLyric
    }
}
