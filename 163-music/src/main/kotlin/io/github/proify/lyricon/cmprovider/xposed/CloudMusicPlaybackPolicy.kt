/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackTrackToken

object CloudMusicPlaybackPolicy {
    const val PLAYBACK_PROCESS = "com.netease.cloudmusic"

    fun isPlaybackProcess(processName: String?): Boolean = processName == PLAYBACK_PROCESS

    fun acceptsDownload(
        current: PlaybackTrackToken?,
        requested: PlaybackTrackToken?,
        responseMediaId: String?
    ): Boolean = PlaybackCommitPolicy.acceptsResult(current, requested, responseMediaId)
}
