/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken

interface DownloadCallback {
    fun onDownloadFinished(
        requestedTrack: PlaybackTrackToken,
        id: String,
        response: String
    )

    fun onDownloadFailed(requestedTrack: PlaybackTrackToken, id: String, e: Exception)
}
