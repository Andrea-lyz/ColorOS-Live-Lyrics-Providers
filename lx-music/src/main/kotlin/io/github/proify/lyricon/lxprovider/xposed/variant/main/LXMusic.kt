/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.lxprovider.xposed.Constants
import io.github.proify.lyricon.lxprovider.xposed.MetadataCache
import io.github.proify.lyricon.lxprovider.xposed.variant.main.Converter.toSong
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class LXMusic(private val lyricModuleClass: String = "cn.toside.music.mobile.lyric.LyricModule") :
    YukiBaseHooker() {
    private companion object {
        private const val TAG = "LXMusicHooker"
    }

    private var isPlaying = false
    private var lastSyncedPosition = 0L
    private var lastUpdateTimeMillis = 0L
    private var playbackRate = 1f
    private var isDisplayTranslation = false
    private var isDisplayRoma = false
    private var lastRichLyric: List<RichLyricLine>? = null
    private var lastSongId: String? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    private val provider: LyriconProvider by lazy {
        val context = appContext ?: error("AppContext is required")
        LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON),
        )
    }

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                provider.register()
                injectLyricModule()
                hookMediaSession()
            }
        }
    }

    private fun injectLyricModule() {
        lyricModuleClass.toClassOrNull()
            ?.resolve()
            ?.apply {
                firstMethod { name = "setLyric" }.hook {
                    after {
                        val lyric = args[0] as? String ?: ""
                        val trans = args[1] as? String
                        val roma = args[2] as? String
                        updateLyric(lyric, trans, roma)
                    }
                }

                firstMethod { name = "play" }.hook {
                    after {
                        val position = (args[0] as? Int ?: 0).toLong()
                        handlePlay(position)
                    }
                }
                firstMethod { name = "pause" }.hook {
                    after {
                        handlePause()
                    }
                }
                firstMethod { name = "setPlaybackRate" }.hook {
                    after {
                        val rate = args[0] as? Float ?: 1f
                        handleSpeedChange(rate)
                    }
                }
                firstMethod { name = "toggleTranslation" }.hook {
                    after {
                        val enabled = args[0] as? Boolean ?: false
                        if (enabled != isDisplayTranslation) {
                            isDisplayTranslation = enabled
                            provider.player.setDisplayTranslation(enabled)
                        }
                    }
                }
                firstMethod { name = "toggleRoma" }.hook {
                    after {
                        val enabled = args[0] as? Boolean ?: false
                        if (enabled != isDisplayRoma) {
                            isDisplayRoma = enabled
                            provider.player.setDisplayRoma(enabled)
                        }
                    }
                }
            }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState
                        provider.player.setPlaybackState(state)
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        MetadataCache.save(mediaMetadata)
                        // 如果已有歌词信息，更新歌曲信息
                        lastRichLyric?.let { richLyric ->
                            lastSongId?.let { songId ->
                                val metadata = MetadataCache.getCurrent()
                                provider.player.setSong(richLyric.toSong(songId, metadata))
                            }
                        }
                    }
                }
            }
    }

    private fun updateLyric(lyric: String, trans: String?, roma: String?) {
        val richLyric = Converter.toRich(lyric, trans, roma)
        lastRichLyric = richLyric
        val songId =
            (lyric.hashCode() + (trans?.hashCode() ?: 0) + (roma?.hashCode() ?: 0)).toString()
        lastSongId = songId
        val metadata = MetadataCache.getCurrent()
        provider.player.setSong(richLyric.toSong(songId, metadata))
    }

    private fun handlePlay(position: Long) {
        updateAnchor(position)
        setPlaybackState(true)
    }

    private fun handlePause() {
        updateAnchor(calculateCurrentPosition())
        setPlaybackState(false)
    }

    private fun handleSpeedChange(newRate: Float) {
        if (playbackRate != newRate) {
            updateAnchor(calculateCurrentPosition())
            playbackRate = newRate
            Log.d(TAG, "Playback rate changed to: $newRate")
        }
    }

    private fun updateAnchor(position: Long) {
        lastSyncedPosition = position
        lastUpdateTimeMillis = System.currentTimeMillis()
    }

    private fun calculateCurrentPosition(): Long {
        if (!isPlaying) return lastSyncedPosition
        val elapsed = System.currentTimeMillis() - lastUpdateTimeMillis
        return lastSyncedPosition + (elapsed * playbackRate).toLong()
    }

    private fun setPlaybackState(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        provider.player.setPlaybackState(playing)

        if (playing) startSyncLoop() else stopSyncLoop()
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                provider.player.setPosition(calculateCurrentPosition())
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }
}
