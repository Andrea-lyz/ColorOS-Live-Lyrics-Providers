/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import io.github.proify.lyricon.yrckit.download.response.LyricResponse

interface DownloadCallback {
    fun onDownloadFinished(
        requestedTrack: PlaybackTrackToken,
        id: Long,
        response: LyricResponse
    )

    fun onDownloadFailed(requestedTrack: PlaybackTrackToken, id: Long, e: Exception)
}
