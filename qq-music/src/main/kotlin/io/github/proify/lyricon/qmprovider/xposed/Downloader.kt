/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import io.github.proify.qrckit.QrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private val downloadingRequests = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun download(requestedTrack: PlaybackTrackToken, downloadCallback: DownloadCallback) {
        val id = requestedTrack.mediaId
        val requestKey = "$id:${requestedTrack.generation}:${requestedTrack.sessionIdentity}"
        if (!downloadingRequests.add(requestKey)) return

        scope.launch {
            try {
                val response = QrcDownloader.downloadLyrics(id)
                downloadCallback.onDownloadFinished(requestedTrack, response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(requestedTrack, e)
            } finally {
                downloadingRequests.remove(requestKey)
            }
        }
    }
}
