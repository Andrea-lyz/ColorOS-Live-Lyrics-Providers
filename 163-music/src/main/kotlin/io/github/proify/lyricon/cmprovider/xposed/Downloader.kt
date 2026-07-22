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
import kotlinx.coroutines.delay
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
                var failure: Exception? = null
                for (attempt in 0 until MAX_ATTEMPTS) {
                    try {
                        val response = YrcDownloader.fetchLyric(id)
                        downloadCallback.onDownloadFinished(requestedTrack, id, response)
                        failure = null
                        break
                    } catch (e: Exception) {
                        failure = e
                        if (attempt + 1 < MAX_ATTEMPTS) {
                            delay(RETRY_DELAY_MS * (attempt + 1))
                        }
                    }
                }
                failure?.let { downloadCallback.onDownloadFailed(requestedTrack, id, it) }
            } finally {
                downloadingRequests.remove(requestKey)
            }
        }
    }

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 350L
}
