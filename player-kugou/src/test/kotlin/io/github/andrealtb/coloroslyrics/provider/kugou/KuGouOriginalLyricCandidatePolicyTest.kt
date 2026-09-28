/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouOriginalLyricCandidatePolicyTest {

    @Test
    fun rejectsChineseVersionCallbackWhileKoreanVersionIsCurrent() {
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                "Catch Catch (Chinese Ver.) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"
            )
        )
    }

    @Test
    fun keepsExpectedTitleAndCapturedSongShortcut() {
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                "\uce90\uce58 \uce90\uce58 (Catch Catch) - YENA (\u5d14\u827a\u5a1c)",
                "\uce90\uce58 \uce90\uce58",
                "YENA"
            )
        )
        val sameSongId = "\ubc45\ubc45\ud589\ud589 (bang bang bang)|bigbang"
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                sameSongId,
                sameSongId,
                "BANG BANG BANG (\ubc45\ubc45\ud589\ud589) - BIGBANG (\ube45\ubc45)",
                "\ubc45\ubc45\ud589\ud589 (bang bang bang)",
                "BIGBANG"
            )
        )
    }

    @Test
    fun rejectsPrefetchedNextTrackFileIdentity() {
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Troye Sivan",
                "She's the Best (Explicit)",
                "Good Times",
                "Lukas Graham"
            )
        )
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Lukas Graham",
                "7 Years",
                "Good Times",
                "Lukas Graham"
            )
        )
    }

    @Test
    fun keepsCurrentAndCarLyricFileIdentity() {
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Lukas Graham",
                "Good Times",
                "Good Times",
                "Lukas Graham"
            )
        )
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Troye Sivan",
                "She's the Best (Explicit)",
                "These days I've been looking back on the lives we've had",
                "Troye Sivan-She\u2019s the Best (Explicit)"
            )
        )
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "BIGBANG (\ube45\ubc45)",
                "BANG BANG BANG (\ubc45\ubc45\ud589\ud589)",
                "\ubc45\ubc45\ud589\ud589 (bang bang bang)",
                "BIGBANG"
            )
        )
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Taylor Swift",
                "I Knew It, I Knew You",
                "I Knew It, I Knew You",
                "Taylor Swift"
            )
        )
    }

    @Test
    fun corruptedSwiftIdentityWouldRejectTheRealFile() {
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "Taylor Swift",
                "I Knew It, I Knew You",
                "Swift",
                "Taylor"
            )
        )
    }

    @Test
    fun multiArtistCreditsInAnotherOrderAreNotForeign() {
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "HOYO-MiX、茶理理理子",
                "Nightglow",
                "Nightglow",
                "茶理理理子/HOYO-MiX"
            )
        )
        assertTrue(
            KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                "HOYO-MiX、茶理理理子",
                "Nightglow",
                "夏日",
                "陈致逸/HOYO-MiX"
            )
        )
    }

    @Test
    fun prefetchedNextSongBindsOnlyToTheSongItsFileNames() {
        val prefetched = "/data/user/0/com.kugou.android.lite/files/kugou/lyrics/" +
            "Lukas Graham - 7 Years-318c587828be1b04d081f39ebbe2719d.krc"
        assertEquals(
            "file",
            KuGouOriginalLyricCandidatePolicy.positiveMatch(prefetched, null, "7 Years", "Lukas Graham")
        )
        assertNull(
            KuGouOriginalLyricCandidatePolicy.positiveMatch(prefetched, null, "Good Times", "Lukas Graham")
        )
        assertNull(
            KuGouOriginalLyricCandidatePolicy.positiveMatch(prefetched, null, "7 Years", "Troye Sivan")
        )
    }

    @Test
    fun titleOnlyFileNameAndLeadingLineArePositiveEvidence() {
        assertEquals(
            "file-title",
            KuGouOriginalLyricCandidatePolicy.positiveMatch(
                "Nightglow-318c587828be1b04d081f39ebfe2719d.krc",
                null,
                "Nightglow",
                "茶理理理子"
            )
        )
        assertNull(
            KuGouOriginalLyricCandidatePolicy.positiveMatch(
                "Nightglow-318c587828be1b04d081f39ebfe2719d.krc",
                null,
                "Nightglow Remix",
                "茶理理理子"
            )
        )
        val hashOnly = "318c587828be1b04d081f39ebfe2719d.krc"
        assertEquals(
            "lead",
            KuGouOriginalLyricCandidatePolicy.positiveMatch(
                hashOnly,
                "Nightglow - 茶理理理子",
                "Nightglow",
                "茶理理理子"
            )
        )
        assertNull(
            KuGouOriginalLyricCandidatePolicy.positiveMatch(
                hashOnly,
                "词：尹纯青",
                "Nightglow",
                "茶理理理子"
            )
        )
    }

    @Test
    fun shortTitlesNeedAnExactFileTitle() {
        assertNull(
            KuGouOriginalLyricCandidatePolicy.positiveMatch(
                "陈奕迅 - 爱情转移-318c587828be1b04d081f39ebfe2719d.krc",
                null,
                "爱情",
                "陈奕迅"
            )
        )
        assertEquals(
            "file",
            KuGouOriginalLyricCandidatePolicy.positiveMatch(
                "陈奕迅 - 爱情-318c587828be1b04d081f39ebfe2719d.krc",
                null,
                "爱情",
                "陈奕迅"
            )
        )
    }

    @Test
    fun candidateMatchingTheOfficialLyricIsTheCurrentSong() {
        val official = KuGouOriginalLyricCandidatePolicy.parseTimedLyric(
            "[00:00.41]希望有羽毛和翅膀 - HOYO-MiX\n" +
                "[00:01.20][00:40.20]作词：某某\n" +
                "[00:12.50]第一句歌词\n" +
                "[00:16.02]第二句歌词\n" +
                "[00:20.30]第三句歌词\n" +
                "[00:24.90]第四句歌词\n"
        )
        assertEquals(7, official.size)
        assertEquals(40_200L, official.first { it.key == "作词某某" && it.timeMs > 10_000L }.timeMs)

        val sameSong = listOf(
            line(410, "Hope Is the Thing With Feathers - Chevy"),
            line(12_520, "第一句歌词"),
            line(16_000, "第二句歌词"),
            line(20_310, "第三句歌词"),
            line(24_880, "第四句歌词")
        )
        assertTrue(KuGouOriginalLyricCandidatePolicy.matchesTimedLyric(official, sameSong))

        val otherSong = listOf(
            line(12_500, "另一首歌"),
            line(16_000, "第二句歌词"),
            line(20_300, "毫不相干"),
            line(24_900, "完全不同")
        )
        assertFalse(KuGouOriginalLyricCandidatePolicy.matchesTimedLyric(official, otherSong))

        val shifted = sameSong.map { line(it.begin + 5_000L, it.text.orEmpty()) }
        assertFalse(KuGouOriginalLyricCandidatePolicy.matchesTimedLyric(official, shifted))
    }

    @Test
    fun placeholderOfficialLyricNeverMatches() {
        val placeholder = KuGouOriginalLyricCandidatePolicy.parseTimedLyric("[00:00.00]纯音乐，请欣赏")
        assertFalse(
            KuGouOriginalLyricCandidatePolicy.matchesTimedLyric(
                placeholder,
                listOf(line(0, "纯音乐，请欣赏"), line(1_000, "a"), line(2_000, "b"))
            )
        )
    }

    private fun line(begin: Long, text: String) =
        io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine(
            begin = begin,
            end = begin + 1_000L,
            text = text
        )

    @Test
    fun parsesArtistTitleFromKuGouFileNames() {
        val spaced = KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(
            "/data/user/0/com.kugou.android.lite/files/kugou/lyrics/" +
                "Lukas Graham - Good Times-318c587828be1b04d081f39ebbe2719d.krc"
        )
        assertNotNull(spaced)
        assertEquals("Lukas Graham", spaced!!.artist)
        assertEquals("Good Times", spaced.title)

        val dashedArtist = KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(
            "AC-DC - Highway to Hell-0123456789abcdef.krc"
        )
        assertNotNull(dashedArtist)
        assertEquals("AC-DC", dashedArtist!!.artist)
        assertEquals("Highway to Hell", dashedArtist.title)
        assertNull(
            KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(
                "Nightglow-318c587828be1b04d081f39ebfe2719d.krc"
            )
        )
    }
}
