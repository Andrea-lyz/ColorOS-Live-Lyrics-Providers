package io.github.proify.lyricon.kwprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuWoLyricInfoPublisherTest {

    @Test
    fun tracksMatchSameTitleArtist() {
        assertTrue(
            KuWoLyricInfoPublisher.tracksMatch(
                metadataTitle = "Go Again (feat. ELYSA)",
                metadataArtist = "King CAAN&ELYSA",
                metadataMediaId = "",
                songName = "Go Again (feat. ELYSA)",
                songArtist = "King CAAN&ELYSA",
                songId = ""
            )
        )
    }

    @Test
    fun tracksMatchIgnoresCaseAndSpacing() {
        assertTrue(
            KuWoLyricInfoPublisher.tracksMatch(
                metadataTitle = "  Go Again (feat. ELYSA) ",
                metadataArtist = "King CAAN&ELYSA",
                metadataMediaId = null,
                songName = "go again (feat. elysa)",
                songArtist = "king caan&elysa",
                songId = null
            )
        )
    }

    @Test
    fun tracksMatchByMediaIdEvenIfTitleDiffers() {
        assertTrue(
            KuWoLyricInfoPublisher.tracksMatch(
                metadataTitle = "We Don't Talk Anymore",
                metadataArtist = "Charlie Puth",
                metadataMediaId = "222503492",
                songName = "Go Again (feat. ELYSA)",
                songArtist = "King CAAN&ELYSA",
                songId = "222503492"
            )
        )
    }

    @Test
    fun tracksMismatchDifferentSongs() {
        assertFalse(
            KuWoLyricInfoPublisher.tracksMatch(
                metadataTitle = "We Don't Talk Anymore",
                metadataArtist = "Charlie Puth&Selena Gomez",
                metadataMediaId = "",
                songName = "Go Again (feat. ELYSA)",
                songArtist = "King CAAN&ELYSA",
                songId = ""
            )
        )
    }

    @Test
    fun tracksMismatchWhenBothMediaIdsDifferEvenIfTitleArtistMatch() {
        assertFalse(
            KuWoLyricInfoPublisher.tracksMatch(
                metadataTitle = "Same Title",
                metadataArtist = "Same Artist",
                metadataMediaId = "new-id",
                songName = "Same Title",
                songArtist = "Same Artist",
                songId = "old-id"
            )
        )
    }

}
