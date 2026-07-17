/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaybackPolicyTest {
    @Test
    fun onlyMainPlaybackProcessIsAllowed() {
        assertTrue(SpotifyPlaybackPolicy.isPlaybackProcess("com.spotify.music"))
        assertFalse(SpotifyPlaybackPolicy.isPlaybackProcess("com.spotify.music:push"))
        assertFalse(SpotifyPlaybackPolicy.isPlaybackProcess("com.spotify.music:download"))
    }

    @Test
    fun staleOrWrongSessionDownloadIsRejected() {
        val current = PlaybackTrackToken("spotify:track:new", 9L, 41)

        assertTrue(SpotifyPlaybackPolicy.acceptsDownload(current, current, current.mediaId))
        assertFalse(
            SpotifyPlaybackPolicy.acceptsDownload(
                current,
                PlaybackTrackToken(current.mediaId, current.generation, 40),
                current.mediaId
            )
        )
        assertFalse(SpotifyPlaybackPolicy.acceptsDownload(current, current, "spotify:track:old"))
    }
}
