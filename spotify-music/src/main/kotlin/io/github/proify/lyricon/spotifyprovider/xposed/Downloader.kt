/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Downloader {
    private val downloadingRequests = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun download(
        requestedTrack: PlaybackTrackToken,
        downloadCallback: DownloadCallback
    ): Job? {
        val id = requestedTrack.mediaId
        val requestKey = "$id:${requestedTrack.generation}:${requestedTrack.sessionIdentity}"
        if (!downloadingRequests.add(requestKey)) return null

        return scope.launch {
            try {
                val response = SpotifyApi.fetchRawLyric(id)
                downloadCallback.onDownloadFinished(requestedTrack, id, response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(requestedTrack, id, e)
            } finally {
                downloadingRequests.remove(requestKey)
            }
        }
    }
}
