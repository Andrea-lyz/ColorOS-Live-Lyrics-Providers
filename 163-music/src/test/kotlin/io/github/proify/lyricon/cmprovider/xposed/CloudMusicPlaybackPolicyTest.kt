/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMusicPlaybackPolicyTest {
    @Test
    fun onlyObservedMediaSessionProcessOwnsProvider() {
        assertTrue(
            CloudMusicPlaybackPolicy.isPlaybackProcess(
                "com.netease.cloudmusic",
                "com.netease.cloudmusic:play"
            )
        )
        assertTrue(
            CloudMusicPlaybackPolicy.isPlaybackProcess(
                "com.hihonor.cloudmusic",
                "com.hihonor.cloudmusic"
            )
        )
        assertFalse(
            CloudMusicPlaybackPolicy.isPlaybackProcess(
                "com.netease.cloudmusic",
                "com.netease.cloudmusic:push"
            )
        )
        assertFalse(
            CloudMusicPlaybackPolicy.isPlaybackProcess(
                "com.hihonor.cloudmusic",
                "com.hihonor.cloudmusic:play"
            )
        )
    }

    @Test
    fun historicalNeteasePlayProcessUsesLightweightMode() {
        assertTrue(
            CloudMusicPlaybackPolicy.isLightweightPlaybackProcess(
                "com.netease.cloudmusic",
                "com.netease.cloudmusic:play"
            )
        )
        assertFalse(
            CloudMusicPlaybackPolicy.isLightweightPlaybackProcess(
                "com.hihonor.cloudmusic",
                "com.hihonor.cloudmusic"
            )
        )
    }

    @Test
    fun responseMustMatchCapturedGeneration() {
        val current = PlaybackTrackToken("200", 20L, 8)

        assertTrue(CloudMusicPlaybackPolicy.acceptsDownload(current, current, "200"))
        assertFalse(
            CloudMusicPlaybackPolicy.acceptsDownload(
                current,
                PlaybackTrackToken("200", 19L, 8),
                "200"
            )
        )
        assertFalse(CloudMusicPlaybackPolicy.acceptsDownload(current, current, "201"))
    }
}
