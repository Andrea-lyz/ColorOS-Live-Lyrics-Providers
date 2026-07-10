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
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.qrckit.LyricResponse

object QQMusic : YukiBaseHooker() {

    private const val TAG = "Lyricon_QQMusic"
    private const val PKG_MAIN = "com.tencent.qqmusic"
    private const val PKG_PLAYER_SERVICE = "com.tencent.qqmusic:QQPlayerService"

    private const val ACTION_LYRIC_SETTINGS_CHANGED =
        "io.github.proify.lyricon.ACTION_SETTINGS_CHANGED"
    private const val PREF_NAME_QQMUSIC = "qqmusicplayer"
    private const val KEY_DISPLAY_TRANS = "showtranslyric"
    private const val KEY_DISPLAY_ROMA = "showromalyric"

    private val mainProcessHook by lazy { MainProcessHook() }
    private val playerProcessHook by lazy { PlayerProcessHook() }

    override fun onHook() {
        val loader = appClassLoader ?: return
        when (processName) {
            PKG_MAIN -> mainProcessHook.hook(loader)
            PKG_PLAYER_SERVICE -> playerProcessHook.hook(loader)
        }
    }

    /**
     * 处理主进程逻辑：监听 QQ 音乐内部设置变更并广播
     */
    private class MainProcessHook {
        fun hook(loader: ClassLoader) {
            YLog.debug("Hooking Main Process: SharedPreferences interceptor")

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
                            Log.d(TAG, "Settings changed in main process: $key -> $value")
                        }
                    }
                }
        }
    }

    private class PlayerProcessHook : DownloadCallback {
        private var lyriconProvider: LyriconProvider? = null
        @Volatile
        private var currentMediaId: String? = null
        @Volatile
        private var bridgeTrack = BridgeTrack()

        private data class BridgeTrack(
            val mediaId: String = "",
            val title: String? = null,
            val artist: String? = null,
            val duration: Long = 0L,
            val generation: Long = 0L
        )

        fun hook(loader: ClassLoader) {
            YLog.debug("Hooking Player Process: MediaSession & Lyricon Provider")

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
                            val state = (args[0] as? PlaybackState) ?: return@after
                            lyriconProvider?.player?.setPlaybackState(state)
                            val currentTrack = bridgeTrack
                            SaltLyricBridge.sendPlaybackState(
                                context = appContext,
                                state = state,
                                mediaId = currentTrack.mediaId,
                                title = currentTrack.title,
                                artist = currentTrack.artist,
                                duration = currentTrack.duration,
                                trackGeneration = currentTrack.generation
                            )
                        }
                    }

                    // 监听歌曲切歌
                    firstMethod {
                        name = "setMetadata"
                        parameters(MediaMetadata::class.java)
                    }.hook {
                        after {
                            val metadata = args[0] as? MediaMetadata ?: return@after
                            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                                ?: return@after

                            if (mediaId.isBlank() || mediaId == currentMediaId) return@after

                            val data = MediaMetadataCache.save(metadata) ?: return@after
                            val nextTrack = synchronized(this@PlayerProcessHook) {
                                if (mediaId == currentMediaId) {
                                    null
                                } else {
                                    val nextGeneration = maxOf(
                                        bridgeTrack.generation + 1L,
                                        SystemClock.elapsedRealtime()
                                    )
                                    BridgeTrack(
                                        mediaId = mediaId,
                                        title = data.title,
                                        artist = data.artist,
                                        duration = data.duration,
                                        generation = nextGeneration
                                    ).also {
                                        currentMediaId = mediaId
                                        bridgeTrack = it
                                    }
                                }
                            } ?: return@after

                            SaltLyricBridge.sendTrackChanged(
                                context = appContext,
                                mediaId = nextTrack.mediaId,
                                title = nextTrack.title,
                                artist = nextTrack.artist,
                                duration = nextTrack.duration,
                                trackGeneration = nextTrack.generation
                            )
                            refreshActiveSong()
                        }
                    }
                }
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
                playerPackageName = PKG_MAIN,
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

        private fun refreshActiveSong() {
            val mediaId = currentMediaId ?: return

            if (DiskSongCache.isCached(mediaId)) {
                updateLyriconSong(DiskSongCache.get(mediaId))
            } else {
                updateSongWithPlaceholder(mediaId)
            }

            DownloadManager.download(mediaId, this)
        }

        private fun updateSongWithPlaceholder(mediaId: String) {
            val metadata = MediaMetadataCache.get(mediaId)

            updateLyriconSong(
                Song(
                    id = mediaId,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    metadata = lyricMetadataOf("placeholder" to "true")
                )
            )
        }

        private fun updateLyriconSong(song: Song?) {
            lyriconProvider?.player?.setSong(song)
            val currentTrack = bridgeTrack
            if (song?.id == currentTrack.mediaId && currentTrack.generation > 0L) {
                SaltLyricBridge.send(appContext, song, currentTrack.generation)
            } else if (!song?.id.isNullOrBlank()) {
                YLog.debug(
                    tag = TAG,
                    msg = "Skip stale Bridge lyric, responseId=${song.id}, " +
                        "currentId=${currentTrack.mediaId}, generation=${currentTrack.generation}"
                )
            }
        }

        override fun onDownloadFinished(response: LyricResponse) {
            val song = response.toLyriconSong()
            DiskSongCache.put(song)

            if (response.id == currentMediaId) {
                updateLyriconSong(song)
            }
        }

        override fun onDownloadFailed(id: String, e: Exception) {
            YLog.error("$TAG: Lyric download failed for $id", e)
        }

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
