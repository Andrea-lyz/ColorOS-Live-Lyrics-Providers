package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackManagerPolicyTest {

    @Test
    fun followsObservedSongUntilMediaSessionEstablishesAuthority() {
        assertTrue(appleBridgeMayFollowObservedSong(null, "queued-track"))
    }

    @Test
    fun followsPlaybackItemMatchingAuthoritativeMediaSessionTrack() {
        assertTrue(appleBridgeMayFollowObservedSong("current-track", "current-track"))
    }

    @Test
    fun ignoresPrefetchedPlaybackItemAfterMediaSessionEstablishesAuthority() {
        assertFalse(appleBridgeMayFollowObservedSong("current-track", "next-track"))
    }
}
