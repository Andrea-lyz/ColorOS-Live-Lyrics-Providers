package io.github.andrealtb.coloroslyrics.provider.universal.api

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongMatchScorerTest {
    @Test
    fun keepsTimedLyrics() {
        val lrc = """
            [ti:Anti-Hero]
            [00:15.20]I have this dream
            [00:18.10]You're a sexy baby
            [00:21.00]And I'm a monster on the hill
        """.trimIndent()
        assertFalse(SongMatchScorer.isInstrumentalOrCreditsOnly(lrc))
    }

    @Test
    fun rejectsCreditsOnly() {
        val lrc = """
            [ti:晴天]
            [00:00.00]词：周杰伦
            [00:01.00]曲：周杰伦
            [00:02.00]纯音乐，请欣赏
        """.trimIndent()
        assertTrue(SongMatchScorer.isInstrumentalOrCreditsOnly(lrc))
    }

    @Test
    fun recognisedCreditPrefixesDoNotCountAsLyrics() {
        val lrc = """
            [00:00.00]词：周杰伦
            [00:02.00]曲：周杰伦
            [00:04.00]编曲：周杰伦
            [00:06.00]合声：周杰伦
        """.trimIndent()
        assertTrue(SongMatchScorer.isInstrumentalOrCreditsOnly(lrc))
    }

    @Test
    fun aMentionOfInstrumentalInsideRealLyricsIsNotPureMusic() {
        val lrc = """
            [00:01.00]I play the instrumental parts
            [00:04.00]And the band keeps time
            [00:07.00]We sing along
        """.trimIndent()
        assertFalse(SongMatchScorer.isInstrumentalOrCreditsOnly(lrc))
    }

    @Test
    fun editionSuffixCannotOutrankTheRealWork() {
        val target = "Babe (Taylor's Version) (From The Vault)"
        val real = SongMatchScorer.calculate(
            target, "Taylor Swift", 224_240L,
            "Babe", "Taylor Swift", 215_000L
        )
        val other = SongMatchScorer.calculate(
            target, "Taylor Swift", 224_240L,
            "Message In A Bottle (From The Vault) (Taylor's Version)", "Taylor Swift", 225_000L
        )
        assertTrue(real > other)
        assertTrue(real >= 0.45)
    }

    @Test
    fun creditedArtistsAreComparedAsASet() {
        val score = SongMatchScorer.calculate(
            "Babe (Taylor's Version) (From The Vault)", "Taylor Swift", 224_240L,
            "Babe", "Sugarland/Taylor Swift", 215_000L
        )
        assertTrue(score >= 0.45)
    }

    @Test
    fun instrumentalUploadIsRejectedForASungTrack() {
        val score = SongMatchScorer.calculate("晴天", "周杰伦", 269_000L, "晴天 (伴奏)", "周杰伦", 269_000L)
        assertEquals(0.0, score, 0.0)
    }

    @Test
    fun missingDurationStillReachesAConfidentScore() {
        val score = SongMatchScorer.calculate(
            "Anti-Hero", "Taylor Swift", null,
            "Anti-Hero", "Taylor Swift", 200_000L
        )
        assertTrue(score >= 0.9)
    }

    @Test
    fun coreTitleDropsEditionLabelsOnly() {
        assertEquals("Babe", SongMatchScorer.coreTitle("Babe (Taylor's Version) (From The Vault)"))
        // A translated subtitle is not an edition label, so the work name keeps it.
        assertEquals("特别的人 (Special Person)", SongMatchScorer.coreTitle("特别的人 (Special Person)"))
        assertEquals("Song (Without You)", SongMatchScorer.coreTitle("Song (Without You)"))
        assertEquals("Stayin' Alive (Alive)", SongMatchScorer.coreTitle("Stayin' Alive (Alive)"))
        assertEquals("晴天", SongMatchScorer.coreTitle("晴天 (Live)"))
        assertEquals("晴天", SongMatchScorer.coreTitle("晴天 (现场版)"))
        assertEquals("Anti-Hero", SongMatchScorer.coreTitle("Anti-Hero"))
    }

    @Test
    fun wordsInsideCoreTitleAreNotPenalizedAsAlternateTakes() {
        assertFalse(SongMatchScorer.hasAlternateTakeMark("Live Forever"))
        assertFalse(SongMatchScorer.hasAlternateTakeMark("Stayin' Alive"))
        assertFalse(SongMatchScorer.hasAlternateTakeMark("Demons"))
        assertFalse(SongMatchScorer.hasAlternateTakeMark("Discover"))
        assertTrue(SongMatchScorer.hasAlternateTakeMark("Live Forever (Live)"))
        assertTrue(SongMatchScorer.hasAlternateTakeMark("Demons - Acoustic"))
        assertTrue(SongMatchScorer.hasAlternateTakeMark("晴天 (现场版)"))
    }
}
