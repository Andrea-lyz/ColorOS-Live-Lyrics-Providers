/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.extensions.bridge.PlaybackTrackToken
import io.github.proify.lyricon.yrckit.download.YrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Downloader {
    private val downloadingRequests = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun download(
        id: Long,
        requestedTrack: PlaybackTrackToken,
        downloadCallback: DownloadCallback
    ) {
        val requestKey = "$id:${requestedTrack.generation}:${requestedTrack.sessionIdentity}"
        if (!downloadingRequests.add(requestKey)) return

        scope.launch {
            try {
                val response = YrcDownloader.fetchLyric(id)
                downloadCallback.onDownloadFinished(requestedTrack, id, response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(requestedTrack, id, e)
            } finally {
                downloadingRequests.remove(requestKey)
            }
        }
    }
}
