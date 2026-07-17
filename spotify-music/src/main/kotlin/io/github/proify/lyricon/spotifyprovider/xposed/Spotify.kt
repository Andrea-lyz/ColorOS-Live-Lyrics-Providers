/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.android.PlaybackStateCommitter
import io.github.proify.extensions.android.ProviderDiagnostics
import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackTrackToken
import io.github.proify.extensions.toPairMap
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.spotifyprovider.xposed.api.NoFoundLyricException
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi.jsonParser
import io.github.proify.lyricon.spotifyprovider.xposed.api.response.LyricResponse
import java.util.Locale
import java.util.concurrent.Executors

object Spotify : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "SpotifyProvider"
    private var lyriconProvider: LyriconProvider? = null
    private var bridgeTrack = BridgeTrack()
    private var lastSong: Song? = null
    private var lastSongTrack: PlaybackTrackToken? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val playbackCommitter = PlaybackStateCommitter()
    private val cacheExecutor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "Spotify-LyricCache").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    private data class BridgeTrack(
        val mediaId: String = "",
        val title: String? = null,
        val artist: String? = null,
        val duration: Long = 0L,
        val generation: Long = 0L,
        val token: PlaybackTrackToken? = null
    )

    override fun onHook() {
        if (!SpotifyPlaybackPolicy.isPlaybackProcess(processName)) {
            ProviderDiagnostics.debug(TAG) { "Skipping non-playback process: $processName" }
            return
        }
        ProviderDiagnostics.debug(TAG) { "Hooking playback process: $processName" }

        onAppLifecycle {
            onCreate { initProvider() }
        }
        hookMediaSession()
        hookOkHttp()
    }

    private fun hookOkHttp() {
        "okhttp3.Headers".toClass()
            .resolve()
            .firstConstructor()
            .hook {
                after {
                    val arg = args[0] as? Array<*> ?: return@after
                    arg.toPairMap().forEach { (key, value) ->
                        val keyLowercase = key.lowercase(Locale.ENGLISH)
                        if (keyLowercase in SpotifyApi.keysRequired) {
                            SpotifyApi.headers[keyLowercase] = value
                        }
                    }
                }
            }
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply { register() }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val session = instance as? MediaSession ?: return@after
                    val state = (args[0] as? PlaybackState) ?: return@after
                    runOnMain { handlePlaybackState(session, state) }
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val session = instance as? MediaSession ?: return@after
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    runOnMain { handleMetadata(session, metadata) }
                }
            }
        }
    }

    private fun handlePlaybackState(session: MediaSession, state: PlaybackState) {
        val acceptedTrack = playbackCommitter.observePlaybackState(session, state) ?: return
        val currentTrack = bridgeTrack
        if (currentTrack.token != acceptedTrack) return
        lyriconProvider?.player?.setPlaybackState(state)
        sendBridgePlaybackState(currentTrack, state)
    }

    private fun handleMetadata(session: MediaSession, metadata: MediaMetadata) {
        val data = MetadataCache.save(metadata)
        if (data == null) {
            ProviderDiagnostics.debug(TAG) { "Ignoring metadata without Spotify track id" }
            return
        }
        val currentToken = bridgeTrack.token
        if (data.id == bridgeTrack.mediaId &&
            currentToken?.sessionIdentity == System.identityHashCode(session)
        ) {
            bridgeTrack = bridgeTrack.copy(
                title = data.title,
                artist = data.artist,
                duration = data.duration
            )
            return
        }

        val generation = PlaybackCommitPolicy.nextGeneration(
            bridgeTrack.generation,
            android.os.SystemClock.elapsedRealtime()
        )
        val token = playbackCommitter.bindTrack(data.id, generation, session) ?: return
        val nextTrack = BridgeTrack(
            mediaId = data.id,
            title = data.title,
            artist = data.artist,
            duration = data.duration,
            generation = generation,
            token = token
        )
        bridgeTrack = nextTrack
        lastSong = null
        lastSongTrack = null

        SaltLyricBridge.sendTrackChanged(
            context = appContext,
            mediaId = nextTrack.mediaId,
            title = nextTrack.title,
            artist = nextTrack.artist,
            duration = nextTrack.duration,
            trackGeneration = nextTrack.generation
        )
        onTrackIdChanged(data, token)
    }

    private fun onTrackIdChanged(data: Metadata, requestedTrack: PlaybackTrackToken) {
        val (id, title, artist) = data
        commitSong(
            Song(id = id, name = title, artist = artist, duration = data.duration),
            requestedTrack
        )
        cacheExecutor.execute {
            val cache = appContext?.let { DiskCache.get(it, id) }
            if (cache != null) {
                applyResponse(requestedTrack, id, cache)
            } else {
                Downloader.download(requestedTrack, this)
            }
        }
    }

    override fun onDownloadFinished(
        requestedTrack: PlaybackTrackToken,
        id: String,
        response: String
    ) {
        applyResponse(requestedTrack, id, response)
        appContext?.let { DiskCache.put(it, id, response) }
    }

    private fun applyResponse(
        requestedTrack: PlaybackTrackToken,
        id: String,
        response: String
    ) {
        val lyricResponse =
            runCatching { jsonParser.decodeFromString<LyricResponse>(response) }.getOrNull()
        if (lyricResponse != null) {
            val song = lyricResponse.toSong(id)
            runOnMain { commitSong(song, requestedTrack) }
        }
    }

    override fun onDownloadFailed(
        requestedTrack: PlaybackTrackToken,
        id: String,
        e: Exception
    ) {
        if (e is NoFoundLyricException) {
            ProviderDiagnostics.debug(TAG) { e.message ?: "No Spotify lyric found for $id" }
        } else {
            YLog.error(tag = TAG, msg = "Failed to fetch lyric for $id", e = e)
        }
    }

    private fun commitSong(song: Song, requestedTrack: PlaybackTrackToken) {
        val currentTrack = bridgeTrack
        if (!SpotifyPlaybackPolicy.acceptsDownload(
                playbackCommitter.currentTrack(),
                requestedTrack,
                song.id
            ) || currentTrack.token != requestedTrack
        ) {
            ProviderDiagnostics.debug(TAG) {
                "Skip stale song commit, responseId=${song.id.orEmpty()}, " +
                    "requestedGeneration=${requestedTrack.generation}, " +
                    "currentId=${currentTrack.mediaId}, currentGeneration=${currentTrack.generation}"
            }
            return
        }
        if (song == lastSong && requestedTrack == lastSongTrack) return

        when (val result = playbackCommitter.commit(
            requestedTrack = requestedTrack,
            responseMediaId = song.id,
            duration = song.duration.takeIf { it > 0L } ?: currentTrack.duration,
            setSong = { lyriconProvider?.player?.setSong(song) },
            setPosition = { lyriconProvider?.player?.setPosition(it) },
            replayPlaybackState = { lyriconProvider?.player?.setPlaybackState(it) },
            publishLyricReady = {
                SaltLyricBridge.send(appContext, song, requestedTrack.generation)
            },
            publishPlaybackState = { state ->
                sendBridgePlaybackState(currentTrack, state, force = true)
            }
        )) {
            is PlaybackStateCommitter.PlaybackCommitResult.Committed -> {
                lastSong = song
                lastSongTrack = requestedTrack
                ProviderDiagnostics.debug(TAG) {
                    "Committed song generation=${requestedTrack.generation}, " +
                        "position=${result.position ?: -1L}"
                }
            }
            is PlaybackStateCommitter.PlaybackCommitResult.Failed -> {
                YLog.error(tag = TAG, msg = "Song commit failed", e = result.throwable)
            }
            PlaybackStateCommitter.PlaybackCommitResult.Rejected -> {
                ProviderDiagnostics.debug(TAG) { "Song commit rejected as stale" }
            }
        }
    }

    private fun sendBridgePlaybackState(
        track: BridgeTrack,
        state: PlaybackState,
        force: Boolean = false
    ) {
        SaltLyricBridge.sendPlaybackState(
            context = appContext,
            state = state,
            mediaId = track.mediaId,
            title = track.title,
            artist = track.artist,
            duration = track.duration,
            trackGeneration = track.generation,
            force = force
        )
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
