/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackTrackToken

object QQPlaybackPolicy {
    const val MAIN_PROCESS = "com.tencent.qqmusic"
    const val PLAYBACK_PROCESS = "com.tencent.qqmusic:QQPlayerService"

    // QQ often resolves cached/network lyrics during its transient MediaSession state reset.
    // Delaying the empty song avoids a visible empty -> populated double render on every switch.
    const val PLACEHOLDER_COMMIT_DELAY_MS = 1_200L

    fun isMainProcess(processName: String?): Boolean = processName == MAIN_PROCESS

    fun isPlaybackProcess(processName: String?): Boolean = processName == PLAYBACK_PROCESS

    fun acceptsDownload(
        current: PlaybackTrackToken?,
        requested: PlaybackTrackToken?,
        responseMediaId: String?
    ): Boolean = PlaybackCommitPolicy.acceptsResult(current, requested, responseMediaId)

    fun shouldCommitPlaceholder(
        current: PlaybackTrackToken?,
        requested: PlaybackTrackToken?,
        hasCommittedLyrics: Boolean
    ): Boolean = current != null && current == requested && !hasCommittedLyrics
}
