/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Metrolist's media metadata is an application-private Media3 model, so the
 * track-change hook remains the authoritative source for IDs and titles. The
 * platform MediaSession hooks are used for the Lyricon playback channel and
 * as a fallback wake-up for metadata changes that arrive outside onEvents.
 */
object Metrolist : YukiBaseHooker() {
    private const val TAG = "MetrolistProvider"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // YukiHookAPI can instantiate this hooker from initZygote before the app's main
    // Looper has been prepared. Resolve the Handler only when a real app callback
    // needs to marshal work onto the main thread.
    private val mainHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }
    private val trackLock = Any()

    @Volatile
    private var lyriconProvider: LyriconProvider? = null

    @Volatile
    private var currentTrack: Track? = null

    @Volatile
    private var hookedMusicService: Any? = null

    private var fetchJob: Job? = null
    private var eventCount = 0

    private data class Track(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSeconds: Int,
        val generation: Long
    ) {
        val durationMillis: Long
            get() = durationSeconds.coerceAtLeast(0).toLong() * 1_000L
    }

    override fun onHook() {
        loadApp(Constants.MUSIC_PACKAGE_NAME) {
            onAppLifecycle {
                onCreate {
                    setupLyriconProvider(this)
                }
            }
            hookMusicService()
            hookMediaSession()
        }
    }

    private fun setupLyriconProvider(application: Application) {
        runCatching {
            lyriconProvider?.destroy()
            val provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = Constants.MUSIC_PACKAGE_NAME,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )
            val registered = provider.register()
            lyriconProvider = provider
            Log.i(TAG, "Lyricon provider registered=$registered")
        }.onFailure { error ->
            Log.w(TAG, "Lyricon provider setup failed", error)
        }
    }

    private fun hookMusicService() {
        try {
            "com.metrolist.music.playback.MusicService".toClass()
                .method {
                    name = "onEvents"
                    paramCount = 2
                }.hook {
                    after {
                        val service = instance ?: return@after
                        hookedMusicService = service
                        try {
                            processTrackChange(service)
                        } catch (error: Throwable) {
                            Log.w(TAG, "processTrackChange error", error)
                        }
                    }
                }
            Log.i(TAG, "Hooked MusicService.onEvents")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to hook MusicService.onEvents", error)
        }
    }

    private fun hookMediaSession() {
        try {
            "android.media.session.MediaSession".toClass().resolve().apply {
                firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    after {
                        val service = hookedMusicService ?: return@after
                        runOnMain { processTrackChange(service) }
                    }
                }

                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args.firstOrNull() as? PlaybackState ?: return@after
                        runOnMain { handlePlaybackState(state) }
                    }
                }
            }
            Log.i(TAG, "Hooked platform MediaSession metadata/playback state")
        } catch (error: Throwable) {
            // Media3 may use a different platform bridge on a future build. The
            // MusicService.onEvents path remains sufficient for lyric delivery.
            Log.w(TAG, "MediaSession hooks unavailable", error)
        }
    }

    private fun processTrackChange(musicService: Any) {
        val metadata = getMediaMetadata(musicService)
        if (metadata == null) {
            if (++eventCount <= 5) {
                Log.d(TAG, "onEvents #$eventCount: metadata is null (no track yet)")
            }
            return
        }

        val mediaId = getFieldValue(metadata, "id") as? String
        val title = getFieldValue(metadata, "title") as? String
        if (mediaId.isNullOrEmpty() || title.isNullOrEmpty()) {
            Log.d(TAG, "metadata found but id/title empty: id=$mediaId title=$title")
            return
        }

        val artist = extractArtist(metadata)
        val album = extractAlbum(metadata)
        val duration = (getFieldValue(metadata, "duration") as? Number)?.toInt() ?: 0
        val previous = currentTrack
        if (previous?.mediaId == mediaId) return

        val context = currentContext()
        val generation = SaltLyricBridge.sendTrackChanged(
            context = context,
            songId = mediaId,
            title = title,
            artist = artist,
            durationSeconds = duration
        )
        val track = Track(mediaId, title, artist, album, duration, generation)
        synchronized(trackLock) {
            fetchJob?.cancel()
            currentTrack = track
            fetchJob = scope.launch { fetchAndPublish(context, track) }
        }

        Log.i(
            TAG,
            "Track changed: id=$mediaId title=$title artist=$artist " +
                "album=${album ?: ""} duration=$duration generation=$generation"
        )
    }

    private suspend fun fetchAndPublish(context: android.content.Context, track: Track) {
        try {
            val result = LyricsFetcher.fetchLyrics(
                context,
                track.title,
                track.artist,
                track.durationSeconds,
                track.album
            )
            if (result == null || result.plainLyric.isEmpty() || !isCurrentTrack(track)) {
                if (result == null || result.plainLyric.isEmpty()) {
                    Log.i(TAG, "No lyrics found for ${track.title}")
                }
                return
            }

            val song = Song(
                id = track.mediaId,
                name = track.title,
                artist = track.artist,
                duration = track.durationMillis,
                lyrics = EnhanceLrcParser.parse(
                    result.plainLyric,
                    track.durationMillis
                ).lines
            )

            withContext(Dispatchers.Main.immediate) {
                if (!isCurrentTrack(track)) return@withContext

                runCatching { lyriconProvider?.player?.setSong(song) }
                    .onFailure { error ->
                        Log.d(TAG, "Lyricon setSong failed: ${error.message}")
                    }
                // The ordering is intentional: preserve Lyricon's normal song
                // path, then publish the same track to the Bridge.
                SaltLyricBridge.send(
                    context = context,
                    songId = track.mediaId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    lyric = result.plainLyric,
                    rawLyric = result.rawLyric,
                    trackGeneration = track.generation
                )
            }
            Log.i(TAG, "Lyrics found for ${track.title} (${result.rawLyric.length} chars)")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Lyrics fetch error: ${track.title}", error)
        }
    }

    private fun handlePlaybackState(state: PlaybackState) {
        val track = currentTrack ?: return
        runCatching {
            val context = currentContext()
            lyriconProvider?.player?.setPlaybackState(state)
            SaltLyricBridge.sendPlaybackState(
                context = context,
                state = state,
                songId = track.mediaId,
                title = track.title,
                artist = track.artist,
                durationMillis = track.durationMillis,
                trackGeneration = track.generation
            )
        }.onFailure { error ->
            Log.d(TAG, "Playback state handling failed: ${error.message}")
        }
    }

    private fun isCurrentTrack(track: Track): Boolean = currentTrack === track

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun getMediaMetadata(musicService: Any): Any? {
        return try {
            val stateFlow = getFieldValue(musicService, "currentMediaMetadata") ?: return null
            stateFlow.javaClass.getMethod("getValue").invoke(stateFlow)
        } catch (error: Throwable) {
            Log.d(TAG, "getMediaMetadata failed: ${error.message}")
            null
        }
    }

    private fun extractArtist(metadata: Any): String {
        return try {
            val artists = getFieldValue(metadata, "artists") as? Iterable<*> ?: return ""
            val names = artists.mapNotNull { artist ->
                artist?.let { getFieldValue(it, "name") as? String }
                    ?.takeIf(String::isNotEmpty)
            }
            names.joinToString(", ")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractAlbum(metadata: Any): String? {
        return try {
            val album = getFieldValue(metadata, "album") ?: return null
            (getFieldValue(album, "title") as? String)?.takeIf(String::isNotBlank)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun currentContext(): android.content.Context {
        val clazz = Class.forName("android.app.ActivityThread")
        val method = clazz.getMethod("currentApplication")
        return method.invoke(null) as android.content.Context
    }
}
