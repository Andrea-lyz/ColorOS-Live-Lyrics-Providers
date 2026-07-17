/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackTrackToken

object SpotifyPlaybackPolicy {
    const val PLAYBACK_PROCESS = "com.spotify.music"

    fun isPlaybackProcess(processName: String?): Boolean = processName == PLAYBACK_PROCESS

    fun acceptsDownload(
        current: PlaybackTrackToken?,
        requested: PlaybackTrackToken?,
        responseMediaId: String?
    ): Boolean = PlaybackCommitPolicy.acceptsResult(current, requested, responseMediaId)
}
