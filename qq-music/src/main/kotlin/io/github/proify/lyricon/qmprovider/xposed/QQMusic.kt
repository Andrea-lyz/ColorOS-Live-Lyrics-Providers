/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.android.PlaybackStateCommitter
import io.github.proify.extensions.android.ProviderDiagnostics
import io.github.proify.extensions.bridge.PlaybackCommitPolicy
import io.github.proify.extensions.bridge.PlaybackTrackToken
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.qrckit.LyricResponse
import java.util.concurrent.Executors

object QQMusic : YukiBaseHooker() {

    private const val TAG = "Lyricon_QQMusic"
    private const val ACTION_LYRIC_SETTINGS_CHANGED =
        "io.github.proify.lyricon.ACTION_SETTINGS_CHANGED"
    private const val PREF_NAME_QQMUSIC = "qqmusicplayer"
    private const val KEY_DISPLAY_TRANS = "showtranslyric"
    private const val KEY_DISPLAY_ROMA = "showromalyric"

    private val mainProcessHook by lazy { MainProcessHook() }
    private val playerProcessHook by lazy { PlayerProcessHook() }

    override fun onHook() {
        val loader = appClassLoader ?: return
        when {
            QQPlaybackPolicy.isMainProcess(processName) -> mainProcessHook.hook(loader)
            QQPlaybackPolicy.isPlaybackProcess(processName) -> playerProcessHook.hook(loader)
        }
    }

    /**
     * 处理主进程逻辑：监听 QQ 音乐内部设置变更并广播
     */
    private class MainProcessHook {
        fun hook(loader: ClassLoader) {
            ProviderDiagnostics.debug(TAG) {
                "Hooking main process SharedPreferences interceptor"
            }

            $$"android.app.SharedPreferencesImpl$EditorImpl".toClass(loader)
                .resolve()
                .firstMethod {
                    name = "putBoolean"
                    parameters(String::class.java, Boolean::class.java)
                }.hook {
                    after {
                        val key = args[0] as String
                        val value = args[1] as Boolean

                        if (key == KEY_DISPLAY_TRANS || key == KEY_DISPLAY_ROMA) {
                            val intent = Intent(ACTION_LYRIC_SETTINGS_CHANGED).apply {
                                putExtra("setting_key", key)
                                putExtra("setting_value", value)
                                setPackage(appContext?.packageName)
                            }
                            appContext?.sendBroadcast(intent)
                            ProviderDiagnostics.debug(TAG) {
                                "Settings changed in main process: $key -> $value"
                            }
                        }
                    }
                }
        }
    }

    private class PlayerProcessHook : DownloadCallback {
        private var lyriconProvider: LyriconProvider? = null
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val playbackCommitter = PlaybackStateCommitter()
        private val cacheExecutor by lazy {
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "QQMusic-LyricCache").apply {
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
        }
        private var bridgeTrack = BridgeTrack()
        private var lastCommittedSong: Song? = null
        private var lastCommittedTrack: PlaybackTrackToken? = null
        private var lastLyriconSong: Song? = null
        private var pendingPlaceholder: Runnable? = null

        private data class BridgeTrack(
            val mediaId: String = "",
            val title: String? = null,
            val artist: String? = null,
            val duration: Long = 0L,
            val generation: Long = 0L,
            val token: PlaybackTrackToken? = null
        )

        private enum class SongCommitSource(val logValue: String) {
            DELAYED_PLACEHOLDER("delayed-placeholder"),
            DISK_CACHE("disk-cache"),
            NETWORK("network")
        }

        fun hook(loader: ClassLoader) {
            ProviderDiagnostics.debug(TAG) {
                "Hooking player process MediaSession and Lyricon provider"
            }

            onAppLifecycle {
                onCreate {
                    DiskSongCache.initialize(this)
                    setupLyriconProvider(this)
                    registerSettingsReceiver(this)
                }
            }

            "android.media.session.MediaSession".toClass(loader)
                .resolve().apply {
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

                    // 监听歌曲切歌
                    firstMethod {
                        name = "setMetadata"
                        parameters(MediaMetadata::class.java)
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
            val data = MediaMetadataCache.save(metadata) ?: return
            val mediaId = data.id
            val currentToken = bridgeTrack.token
            if (mediaId == bridgeTrack.mediaId &&
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
            val token = playbackCommitter.bindTrack(
                mediaId = mediaId,
                generation = generation,
                session = session,
                // QQ updates metadata before the reused session leaves the previous track state.
                reusePreBindState = false
            ) ?: return
            val nextTrack = BridgeTrack(
                mediaId = mediaId,
                title = data.title,
                artist = data.artist,
                duration = data.duration,
                generation = generation,
                token = token
            )
            cancelPendingPlaceholder()
            bridgeTrack = nextTrack
            lastCommittedSong = null
            lastCommittedTrack = null

            SaltLyricBridge.sendTrackChanged(
                context = appContext,
                mediaId = nextTrack.mediaId,
                title = nextTrack.title,
                artist = nextTrack.artist,
                duration = nextTrack.duration,
                trackGeneration = nextTrack.generation
            )
            refreshActiveSong(nextTrack)
        }

        private fun registerSettingsReceiver(application: Application) {
            val filter = IntentFilter(ACTION_LYRIC_SETTINGS_CHANGED)

            ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val key = intent?.getStringExtra("setting_key") ?: return
                    val value = intent.getBooleanExtra("setting_value", false)

                    when (key) {
                        KEY_DISPLAY_TRANS -> lyriconProvider?.player?.setDisplayTranslation(value)
                        KEY_DISPLAY_ROMA -> lyriconProvider?.player?.setDisplayRoma(value)
                    }
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        private fun setupLyriconProvider(application: Application) {
            val provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = QQPlaybackPolicy.MAIN_PROCESS,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )

            // 初始化显示设置
            val prefs = application.getSharedPreferences(PREF_NAME_QQMUSIC, Context.MODE_PRIVATE)
            provider.player.apply {
                setDisplayTranslation(prefs.getBoolean(KEY_DISPLAY_TRANS, false))
                setDisplayRoma(prefs.getBoolean(KEY_DISPLAY_ROMA, false))
            }

            provider.register()
            this.lyriconProvider = provider
        }

        // --- 歌曲数据处理 ---

        private fun refreshActiveSong(track: BridgeTrack) {
            val token = track.token ?: return
            schedulePlaceholder(track, token)
            cacheExecutor.execute {
                val cachedSong = DiskSongCache.get(track.mediaId)
                if (!cachedSong?.lyrics.isNullOrEmpty()) {
                    runOnMain {
                        commitSong(cachedSong, token, source = SongCommitSource.DISK_CACHE)
                    }
                }
                DownloadManager.download(token, this)
            }
        }

        private fun schedulePlaceholder(track: BridgeTrack, token: PlaybackTrackToken) {
            val task = Runnable {
                pendingPlaceholder = null
                if (!QQPlaybackPolicy.shouldCommitPlaceholder(
                        current = playbackCommitter.currentTrack(),
                        requested = token,
                        hasCommittedLyrics = hasRenderableLyrics(lastCommittedSong)
                    ) || bridgeTrack.token != token
                ) {
                    return@Runnable
                }
                commitSong(
                    song = Song(
                        id = track.mediaId,
                        name = track.title,
                        artist = track.artist,
                        duration = track.duration,
                        metadata = lyricMetadataOf("placeholder" to "true")
                    ),
                    requestedTrack = token,
                    source = SongCommitSource.DELAYED_PLACEHOLDER
                )
            }
            pendingPlaceholder = task
            mainHandler.postDelayed(task, QQPlaybackPolicy.PLACEHOLDER_COMMIT_DELAY_MS)
        }

        private fun cancelPendingPlaceholder() {
            pendingPlaceholder?.let(mainHandler::removeCallbacks)
            pendingPlaceholder = null
        }

        private fun commitSong(
            song: Song,
            requestedTrack: PlaybackTrackToken,
            source: SongCommitSource
        ) {
            val currentTrack = bridgeTrack
            if (!QQPlaybackPolicy.acceptsDownload(
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
            val preparedSong = song.copy(
                id = currentTrack.mediaId,
                name = currentTrack.title?.takeIf(String::isNotBlank) ?: song.name,
                artist = currentTrack.artist?.takeIf(String::isNotBlank) ?: song.artist,
                duration = currentTrack.duration.takeIf { it > 0L } ?: song.duration
            )
            val hasLyrics = hasRenderableLyrics(preparedSong)
            if (hasLyrics || source == SongCommitSource.NETWORK) cancelPendingPlaceholder()
            if (hasRenderableLyrics(lastCommittedSong) && !hasLyrics &&
                requestedTrack == lastCommittedTrack
            ) {
                return
            }
            if (preparedSong == lastCommittedSong && requestedTrack == lastCommittedTrack) return
            publishLyriconSong(preparedSong)

            when (val result = playbackCommitter.commit(
                requestedTrack = requestedTrack,
                responseMediaId = preparedSong.id,
                duration = preparedSong.duration.takeIf { it > 0L } ?: currentTrack.duration,
                // Keep the upstream Lyricon delivery outside Bridge-only generation gating.
                setSong = {},
                setPosition = { lyriconProvider?.player?.setPosition(it) },
                replayPlaybackState = { lyriconProvider?.player?.setPlaybackState(it) },
                publishLyricReady = {
                    SaltLyricBridge.send(appContext, preparedSong, requestedTrack.generation)
                },
                publishPlaybackState = { state ->
                    sendBridgePlaybackState(currentTrack, state, force = true)
                },
                // Only states observed after bind belong to this QQ track generation.
                refreshStateFromSession = false
            )) {
                is PlaybackStateCommitter.PlaybackCommitResult.Committed -> {
                    lastCommittedSong = preparedSong
                    lastCommittedTrack = requestedTrack
                    ProviderDiagnostics.debug(TAG) {
                        "Committed song source=${source.logValue}, " +
                            "generation=${requestedTrack.generation}, " +
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

        private fun publishLyriconSong(song: Song) {
            if (lastLyriconSong == song) return
            val player = lyriconProvider?.player ?: return
            player.setSong(song)
            lastLyriconSong = song
        }

        override fun onDownloadFinished(
            requestedTrack: PlaybackTrackToken,
            response: LyricResponse
        ) {
            val song = response.toLyriconSong()
            DiskSongCache.put(song)
            runOnMain { commitSong(song, requestedTrack, source = SongCommitSource.NETWORK) }
        }

        override fun onDownloadFailed(requestedTrack: PlaybackTrackToken, e: Exception) {
            YLog.error("$TAG: Lyric download failed for ${requestedTrack.mediaId}", e)
            runOnMain {
                if (playbackCommitter.currentTrack() != requestedTrack
                    || bridgeTrack.token != requestedTrack
                ) {
                    return@runOnMain
                }
                pendingPlaceholder?.let { placeholder ->
                    mainHandler.removeCallbacks(placeholder)
                    placeholder.run()
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

        private fun hasRenderableLyrics(song: Song?): Boolean = !song?.lyrics.isNullOrEmpty()

        private fun LyricResponse.toLyriconSong(): Song {
            val cachedMetadata = MediaMetadataCache.get(id)
            val lyrics = parsedLyric.richLyricLines.removeInvalidTranslation()
            return Song(
                id = id,
                name = cachedMetadata?.title,
                artist = cachedMetadata?.artist,
                duration = cachedMetadata?.duration ?: 0,
                lyrics = lyrics
            )
        }

        fun List<RichLyricLine>.removeInvalidTranslation() = apply {
            forEach { if (it.translation?.trim() == "//") it.translation = null }
        }
    }
}
