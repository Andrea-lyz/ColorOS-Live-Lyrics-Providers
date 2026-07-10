/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null
    private var application: Application? = null

    private var currentSongId: String? = null
    private var currentPlaybackItem: Any? = null
    private var lastLyricRequestKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackItemsById = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > 24
    }
    private var lyricRequestGeneration = 0L
    private var lyricRequestAttempts = 0
    private var lyricRetryPending = false
    @Volatile
    private var bridgeTrack = BridgeTrack()

    private data class BridgeTrack(
        val mediaId: String = "",
        val title: String? = null,
        val artist: String? = null,
        val duration: Long = 0L,
        val generation: Long = 0L
    )

    fun init(remotePlayer: RemotePlayer, requester: LyricRequester, application: Application) {
        this.player = remotePlayer
        this.lyricRequester = requester
        this.application = application
    }

    fun onSongChanged(newId: String?) {
        onSongChanged(newId, requestIfMissing = true)
    }

    fun onPlaybackItemObserved(
        playbackItem: Any?,
        requestIfMissing: Boolean
    ) {
        val observedPlaybackItem = playbackItem ?: return
        val metadata = MediaMetadataCache.putPlaybackItem(observedPlaybackItem) ?: return

        synchronized(playbackItemsById) {
            playbackItemsById[metadata.id] = observedPlaybackItem
        }
        currentPlaybackItem = observedPlaybackItem
        onSongChanged(metadata.id, requestIfMissing)
    }

    private fun onSongChanged(newId: String?, requestIfMissing: Boolean) {
        if (newId.isNullOrBlank()) {
            currentSongId = null
            currentPlaybackItem = null
            lastLyricRequestKey = null
            bridgeTrack = BridgeTrack()
            lyricRequestGeneration = 0L
            lyricRequestAttempts = 0
            lyricRetryPending = false
            setSong(null)
            return
        }

        if (newId == currentSongId) {
            if (requestIfMissing && lastSong?.lyrics.isNullOrEmpty()) {
                requestLyrics(newId)
            }
            return
        }
        val metadata = MediaMetadataCache.getMetadataById(newId)
        beginBridgeTrack(
            mediaId = newId,
            title = metadata?.title,
            artist = metadata?.artist,
            duration = metadata?.duration ?: 0L
        )

        val song = SongRepository.getSong(newId)
        setSong(song)

        if (song.lyrics.isNullOrEmpty() && requestIfMissing) {
            requestLyrics(newId)
        }
    }

    fun onLyricsBuilt(nativeSongObj: Any) {
        val song = SongRepository.saveSong(nativeSongObj) ?: return
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        if (currentSongId == null) {
            beginBridgeTrack(
                mediaId = id,
                title = song.name,
                artist = song.artist,
                duration = song.duration
            )
        }

        if (id == currentSongId && lastSong != song) {
            setSong(song)
        }
    }

    private var lastSong: Song? = null
    private fun setSong(song: Song?) {
        lastSong = song
        player?.setSong(song)
        sendCurrentBridgeSong(song)
    }

    fun onBridgeMediaSessionPlaybackStateChanged(playbackState: PlaybackState) {
        val currentTrack = bridgeTrack
        if (currentTrack.generation <= 0L) return
        SaltLyricBridge.sendPlaybackState(
            context = application,
            playbackState = playbackState.state,
            playbackPosition = playbackState.position,
            playbackSpeed = playbackState.playbackSpeed,
            playbackLastPositionUpdateTime = playbackState.lastPositionUpdateTime,
            mediaId = currentTrack.mediaId,
            title = currentTrack.title,
            artist = currentTrack.artist,
            duration = currentTrack.duration,
            trackGeneration = currentTrack.generation
        )
    }

    private fun beginBridgeTrack(
        mediaId: String,
        title: String?,
        artist: String?,
        duration: Long
    ) {
        val nextTrack = synchronized(this) {
            if (currentSongId == mediaId && bridgeTrack.generation > 0L) {
                null
            } else {
                val nextGeneration = maxOf(
                    bridgeTrack.generation + 1L,
                    SystemClock.elapsedRealtime()
                )
                BridgeTrack(
                    mediaId = mediaId,
                    title = title,
                    artist = artist,
                    duration = duration,
                    generation = nextGeneration
                ).also {
                    currentSongId = mediaId
                    currentPlaybackItem = synchronized(playbackItemsById) {
                        playbackItemsById[mediaId]
                    }
                    bridgeTrack = it
                    lastLyricRequestKey = null
                    lyricRequestGeneration = it.generation
                    lyricRequestAttempts = 0
                    lyricRetryPending = false
                }
            }
        } ?: return

        SaltLyricBridge.sendTrackChanged(
            context = application,
            mediaId = nextTrack.mediaId,
            title = nextTrack.title,
            artist = nextTrack.artist,
            duration = nextTrack.duration,
            trackGeneration = nextTrack.generation
        )
    }

    private fun sendCurrentBridgeSong(song: Song?) {
        val currentTrack = bridgeTrack
        if (song?.id == currentTrack.mediaId && currentTrack.generation > 0L) {
            SaltLyricBridge.send(application, song, currentTrack.generation)
        } else if (!song?.id.isNullOrBlank()) {
            YLog.debug(
                tag = "Lyricon_AppleMusic",
                msg = "Skip stale Bridge lyric, responseId=${song.id}, " +
                    "currentId=${currentTrack.mediaId}, generation=${currentTrack.generation}"
            )
        }
    }

    private fun requestLyrics(mediaId: String) {
        val currentTrack = bridgeTrack
        if (currentTrack.mediaId != mediaId || currentTrack.generation <= 0L) return
        if (lastSong?.id == mediaId && !lastSong?.lyrics.isNullOrEmpty()) return
        if (lyricRequestGeneration != currentTrack.generation || lyricRequestAttempts >= 3) return
        if (lyricRetryPending && lyricRequestAttempts > 0) return

        val playbackItem = synchronized(playbackItemsById) {
            playbackItemsById[mediaId]
        } ?: currentPlaybackItem
        val playbackItemMetadata = MediaMetadataCache.putPlaybackItem(playbackItem)
        if (playbackItem == null || playbackItemMetadata?.id != mediaId) {
            YLog.debug(
                tag = "Lyricon_AppleMusic",
                msg = "Wait for matching PlaybackItem before Bridge lyric request, " +
                    "mediaId=$mediaId, generation=${currentTrack.generation}"
            )
            return
        }

        val requestKey = "$mediaId:playbackItem:${System.identityHashCode(playbackItem)}"
        if (requestKey == lastLyricRequestKey) {
            return
        }

        lastLyricRequestKey = requestKey
        lyricRequestAttempts++
        val attempt = lyricRequestAttempts
        val requested = lyricRequester?.requestDownload(playbackItem) == true
        YLog.debug(
            tag = "Lyricon_AppleMusic",
            msg = "Requested Bridge lyric from matching PlaybackItem, mediaId=$mediaId, " +
                "generation=${currentTrack.generation}, attempt=$attempt, accepted=$requested"
        )
        if (attempt < 3 && lastSong?.lyrics.isNullOrEmpty()) {
            scheduleLyricRequestRetry(
                mediaId = mediaId,
                generation = currentTrack.generation,
                delayMs = if (attempt == 1) 1_500L else 4_000L
            )
        }
    }

    private fun scheduleLyricRequestRetry(
        mediaId: String,
        generation: Long,
        delayMs: Long
    ) {
        if (lyricRetryPending) return
        lyricRetryPending = true
        mainHandler.postDelayed({
            if (bridgeTrack.generation != generation || bridgeTrack.mediaId != mediaId) {
                return@postDelayed
            }
            lyricRetryPending = false
            if (lastSong?.id == mediaId && !lastSong?.lyrics.isNullOrEmpty()) {
                return@postDelayed
            }
            lastLyricRequestKey = null
            requestLyrics(mediaId)
        }, delayMs)
    }
}
