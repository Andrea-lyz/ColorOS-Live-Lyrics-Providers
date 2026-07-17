/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQPlaybackPolicyTest {
    @Test
    fun processRolesAreExact() {
        assertTrue(QQPlaybackPolicy.isMainProcess("com.tencent.qqmusic"))
        assertTrue(QQPlaybackPolicy.isPlaybackProcess("com.tencent.qqmusic:QQPlayerService"))
        assertFalse(QQPlaybackPolicy.isPlaybackProcess("com.tencent.qqmusic"))
        assertFalse(QQPlaybackPolicy.isPlaybackProcess("com.tencent.qqmusic:push"))
    }

    @Test
    fun oldDownloadCannotReplaceCurrentTrack() {
        val current = PlaybackTrackToken("new", 12L, 3)
        val old = PlaybackTrackToken("old", 11L, 3)

        assertFalse(QQPlaybackPolicy.acceptsDownload(current, old, "old"))
        assertTrue(QQPlaybackPolicy.acceptsDownload(current, current, "new"))
    }

    @Test
    fun delayedPlaceholderCannotReplaceResolvedOrNewerTrack() {
        val requested = PlaybackTrackToken("song", 12L, 3)
        val newer = PlaybackTrackToken("next", 13L, 3)

        assertTrue(QQPlaybackPolicy.shouldCommitPlaceholder(requested, requested, false))
        assertFalse(QQPlaybackPolicy.shouldCommitPlaceholder(requested, requested, true))
        assertFalse(QQPlaybackPolicy.shouldCommitPlaceholder(newer, requested, false))
        assertFalse(QQPlaybackPolicy.shouldCommitPlaceholder(null, requested, false))
    }
}
