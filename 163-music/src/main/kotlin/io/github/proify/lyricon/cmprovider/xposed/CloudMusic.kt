/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.cmprovider.xposed

import android.app.Application
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
import io.github.proify.extensions.json
import io.github.proify.lyricon.cmprovider.xposed.Constants.ICON
import io.github.proify.lyricon.cmprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import io.github.proify.lyricon.cmprovider.xposed.PreferencesMonitor.PreferenceCallback
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.yrckit.download.response.LyricResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * 网易云音乐模块主入口，根据进程名选择性启用歌词提供者钩子。
 */
object CloudMusic : YukiBaseHooker() {
    private const val TAG = "CloudMusicProvider"
    private val providerManager by lazy { LyricProviderManager() }

    override fun onHook() {
        if (CloudMusicPlaybackPolicy.isPlaybackProcess(packageName, processName)) {
            ProviderDiagnostics.debug(TAG) { "Hooking authoritative process $processName" }
            // The historical 9.0.40 APK is LSPatch-wrapped and publishes its
            // MediaSession from :play.  Its sourceDir points at the wrapper,
            // not the original dex container; loading/scanning it with DexKit
            // can abort the process before any hook is installed.  The play
            // process does not need preference discovery, so keep it in the
            // lightweight MediaSession/PlayService mode below.
            if (providerManager.shouldUseDexKit) {
                runCatching { System.loadLibrary("dexkit") }
                    .onFailure { error ->
                        ProviderDiagnostics.debug(TAG) {
                            "DexKit native library unavailable: ${error.javaClass.simpleName}"
                        }
                    }
            }
            providerManager.onHook()
        } else {
            ProviderDiagnostics.debug(TAG) { "Skipping non-playback process $processName" }
        }
    }

    /**
     * 歌词提供者核心管理器，负责设置钩子、管理提供者生命周期、处理歌词下载与缓存。
     */
    private class LyricProviderManager : DownloadCallback {
        private var lyricProvider: LyriconProvider? = null
        private var lastSetSong: Song? = null
        private var lastSetTrack: PlaybackTrackToken? = null
        private var lastBridgeSong: Song? = null
        private var currentMusicId: Long = 0
        private var bridgeTrack = BridgeTrack()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val playbackCommitter = PlaybackStateCommitter()
        private val cacheExecutor by lazy {
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "CloudMusic-LyricCache").apply {
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

        private var dexKitBridge: DexKitBridge? = null
        private var preferencesMonitor: PreferencesMonitor? = null

        private var translationType: Int = 114514
        private var lastMediaSession: MediaSession? = null
        private var pendingInternalMetadata: Metadata? = null
        private var internalMetadataHookLoader: ClassLoader? = null

        private val preferenceCallback = object : PreferenceCallback {
            override fun onTranslationOptionChanged(type: Int) {
                runOnMainSafely("Translation preference") {
                    if (translationType == type) return@runOnMainSafely
                    translationType = type
                    ProviderDiagnostics.debug(TAG) { "Translation type changed to $type" }
                    lyricProvider?.player?.setDisplayTranslation(type == 0)
                    lyricProvider?.player?.setDisplayRoma(type == 1)
                    sendCurrentBridgeSong(lastBridgeSong)
                }
            }
        }

        private val lightweightPlaybackProcess =
            CloudMusicPlaybackPolicy.isLightweightPlaybackProcess(packageName, processName)

        /**
         * The supplied slim APK embeds Dolby's LSPatch module.  That module
         * contributes its own libdexkit.so to the host APK, with a different
         * ABI/version from LyricProvider's DexKit.  Android may resolve the
         * short library name to the host copy, so do not load or scan DexKit
         * whenever the host already bundles one.
         */
        private val hostBundlesDexKit by lazy {
            runCatching {
                ZipFile(appInfo.sourceDir).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        !entry.isDirectory && entry.name.startsWith("lib/") &&
                            entry.name.endsWith("/libdexkit.so")
                    }
                }
            }.getOrDefault(false)
        }

        val shouldUseDexKit: Boolean
            get() = !lightweightPlaybackProcess && !hostBundlesDexKit

        // ---------------------------------- 入口与初始化 ----------------------------------

        fun onHook() {
            ProviderDiagnostics.debug(TAG) { "Hooking process=$processName" }

            if (shouldUseDexKit) {
                runCatching {
                    val bridge = DexKitBridge.create(appInfo.sourceDir)
                    dexKitBridge = bridge
                    preferencesMonitor = PreferencesMonitor(bridge, preferenceCallback)
                }.onFailure { error ->
                    // A failed optional preference scan must not take down the
                    // player's process. Fall back to the version-specific
                    // accessor that does not require a native library.
                    preferencesMonitor = PreferencesMonitor(null, preferenceCallback)
                    ProviderDiagnostics.debug(TAG) {
                        "DexKit preference scan unavailable, using known accessor: " +
                            error.javaClass.simpleName
                    }
                }
            } else {
                preferencesMonitor = PreferencesMonitor(null, preferenceCallback)
                ProviderDiagnostics.debug(TAG) {
                    val reason = when {
                        lightweightPlaybackProcess -> "historical :play process"
                        hostBundlesDexKit -> "host APK already bundles libdexkit.so"
                        else -> "unsupported process"
                    }
                    "Skipping DexKit preference scan ($reason)"
                }
            }

            if (!shouldUseDexKit) {
                appClassLoader?.let { preferencesMonitor?.update(it) }
            }

            onAppLifecycle {
                onCreate {
                    runCatching { setupProvider() }
                        .onFailure { error ->
                            ProviderDiagnostics.debug(TAG) {
                                "Provider setup failed; keeping player alive: ${error.javaClass.simpleName}"
                            }
                        }
                }
            }

            if (shouldUseDexKit) {
                rehookAfterTinkerLoad(appClassLoader!!)
            }
            hookMediaSession()
            hookInternalMetadata(appClassLoader!!)
        }

        /**
         * 在 Tinker 热更新后重新挂钩必要的类（如偏好设置监听）。
         */
        private fun rehookAfterTinkerLoad(classLoader: ClassLoader) {
            "com.tencent.tinker.loader.TinkerLoader".toClass(appClassLoader)
                .resolve()
                .method { name = "tryLoad" }
                .forEach {
                    it.hook {
                        after {
                            val app = args[0] as Application
                            rehookAfterTinkerLoad(app.classLoader)
                        }
                    }
                }

            preferencesMonitor?.update(classLoader)
            hookInternalMetadata(classLoader)
        }

        /**
         * 初始化并注册 LyriconProvider。
         */
        private fun setupProvider() {
            val application = appContext ?: return
            lyricProvider?.destroy()

            lyricProvider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromSvg(ICON)
            ).apply {
                val type = preferencesMonitor?.getTranslationType() ?: -1
                translationType = type
                player.setDisplayTranslation(type == 0)
                player.setDisplayRoma(type == 1)

                register()
            }

            ProviderDiagnostics.debug(TAG) { "Provider registered" }
        }

        // ---------------------------------- MediaSession 钩子 ----------------------------------

        private fun hookMediaSession() {
            runCatching {
                "android.media.session.MediaSession".toClass()
                    .resolve()
                    .apply {
                        firstMethod {
                            name = "setMetadata"
                            parameters(MediaMetadata::class.java)
                        }.hook {
                            after {
                                val session = instance as? MediaSession ?: return@after
                                val metadata = args[0] as? MediaMetadata ?: return@after
                                lastMediaSession = session
                                runOnMainSafely("MediaSession metadata") {
                                    handleMetadata(session, metadata)
                                }
                            }
                        }

                        firstMethod {
                            name = "setPlaybackState"
                            parameters(PlaybackState::class.java)
                        }.hook {
                            after {
                                val session = instance as? MediaSession ?: return@after
                                val state = args[0] as? PlaybackState ?: return@after
                                runOnMainSafely("MediaSession playback state") {
                                    handlePlaybackState(session, state)
                                }
                            }
                        }
                    }
            }.onFailure { error ->
                ProviderDiagnostics.debug(TAG) {
                    "MediaSession hooks unavailable: ${error.javaClass.simpleName}"
                }
            }
        }

        /**
         * MediaSession metadata is not populated consistently by the Honor
         * build. PlayService still receives a stable BizMusicMeta callback in
         * both target APKs, so use it as a lyric-local fallback and let the
         * next MediaSession callback provide the authoritative session token.
         */
        private fun hookInternalMetadata(classLoader: ClassLoader) {
            if (internalMetadataHookLoader === classLoader) return
            runCatching {
                "com.netease.cloudmusic.service.PlayService".toClass(classLoader)
                    .resolve()
                    .firstMethod {
                        name = "onMetadataChanged"
                        parameterCount = 1
                    }
                    .hook {
                        after {
                            runCatching {
                                val value = args.firstOrNull() ?: return@runCatching
                                val data = MediaMetadataCache.saveBiz(value) ?: return@runCatching
                                runOnMainSafely("PlayService metadata") {
                                    pendingInternalMetadata = data
                                    lastMediaSession?.let { session ->
                                        pendingInternalMetadata = null
                                        handleMetadata(session, data)
                                    }
                                }
                            }.onFailure { error ->
                                ProviderDiagnostics.debug(TAG) {
                                    "PlayService metadata callback ignored: ${error.javaClass.simpleName}"
                                }
                            }
                        }
                    }
                internalMetadataHookLoader = classLoader
                ProviderDiagnostics.debug(TAG) { "PlayService metadata fallback hooked" }
            }.onFailure { error ->
                ProviderDiagnostics.debug(TAG) {
                    "PlayService metadata fallback unavailable: ${error.javaClass.simpleName}"
                }
            }
        }

        private fun handleMetadata(session: MediaSession, metadata: MediaMetadata) {
            val data = MediaMetadataCache.save(metadata)
                ?: pendingInternalMetadata?.also { pendingInternalMetadata = null }
                ?: return
            handleMetadata(session, data)
        }

        private fun handleMetadata(session: MediaSession, data: Metadata) {
            refreshTranslationPreference()
            val currentToken = bridgeTrack.token
            if (currentMusicId == data.id &&
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
            val token = playbackCommitter.bindTrack(data.id.toString(), generation, session) ?: return
            val nextTrack = BridgeTrack(
                mediaId = data.id.toString(),
                title = data.title,
                artist = data.artist,
                duration = data.duration,
                generation = generation,
                token = token
            )
            currentMusicId = data.id
            bridgeTrack = nextTrack
            lastSetSong = null
            lastSetTrack = null
            lastBridgeSong = null

            SaltLyricBridge.sendTrackChanged(
                context = appContext,
                mediaId = nextTrack.mediaId,
                title = nextTrack.title,
                artist = nextTrack.artist,
                duration = nextTrack.duration,
                trackGeneration = nextTrack.generation
            )
            onSongChanged(data, token)
        }

        /**
         * Multiprocess preference implementations do not always dispatch a
         * listener callback into :play. Re-read on track changes as a cheap
         * synchronization fallback.
         */
        private fun refreshTranslationPreference() {
            val type = preferencesMonitor?.getTranslationType() ?: return
            if (type >= 0 && type != translationType) {
                preferenceCallback.onTranslationOptionChanged(type)
            }
        }

        private fun handlePlaybackState(session: MediaSession, state: PlaybackState) {
            pendingInternalMetadata?.let { data ->
                pendingInternalMetadata = null
                handleMetadata(session, data)
            }
            val acceptedTrack = playbackCommitter.observePlaybackState(session, state) ?: return
            val currentTrack = bridgeTrack
            if (currentTrack.token != acceptedTrack) return
            lyricProvider?.player?.setPlaybackState(state)
            sendBridgePlaybackState(currentTrack, state)
        }

        // ---------------------------------- 下载回调实现 ----------------------------------

        override fun onDownloadFinished(
            requestedTrack: PlaybackTrackToken,
            id: Long,
            response: LyricResponse
        ) {
            ProviderDiagnostics.debug(TAG) { "Download finished: $id" }
            val song = writeToLocalLyricCache(id, response)
            runOnMain { commitSong(song, requestedTrack) }
        }

        override fun onDownloadFailed(
            requestedTrack: PlaybackTrackToken,
            id: Long,
            e: Exception
        ) {
            YLog.error(tag = TAG, msg = "Download failed: $id, e=$e")
        }

        // ---------------------------------- 本地缓存读写 ----------------------------------

        private fun getDownloadLyricFile(id: Long): File =
            File(Constants.getDownloadLyricDirectory(appContext!!), id.toString())

        @OptIn(ExperimentalSerializationApi::class)
        private fun writeToLocalLyricCache(id: Long, response: LyricResponse): Song {
            val outputFile = getDownloadLyricFile(id)
            val cacheEntry = LocalLyricCache(
                musicId = id,
                lrc = response.lrc?.lyric,
                lrcTranslateLyric = response.tlyric?.lyric,
                yrc = response.yrc?.lyric,
                yrcTranslateLyric = response.ytlrc?.lyric,
                pureMusic = response.pureMusic,
                roma = response.romalrc?.lyric
            )

            outputFile.outputStream().use { outputStream ->
                json.encodeToStream(cacheEntry, outputStream)
            }
            return songFromCache(MediaMetadataCache.get(id), cacheEntry)
        }

        // ---------------------------------- 歌曲变更处理 ----------------------------------

        private fun onSongChanged(metadata: Metadata, requestedTrack: PlaybackTrackToken) {
            val newMusicId = metadata.id
            commitSong(songFromCache(metadata, null), requestedTrack)
            cacheExecutor.execute {
                val localCacheFile = getDownloadLyricFile(newMusicId)
                if (localCacheFile.exists()) {
                    val song = loadSong(metadata, localCacheFile)
                    runOnMain { commitSong(song, requestedTrack) }
                } else {
                    Downloader.download(newMusicId, requestedTrack, this)
                }
            }
        }

        /** 从缓存文件解析歌曲；磁盘与 JSON 操作始终在缓存执行器运行。 */
        private fun loadSong(metadata: Metadata, cacheFile: File): Song {
            return runCatching {
                val cache = json.decodeFromString<LocalLyricCache>(cacheFile.readText())
                songFromCache(metadata, cache)
            }.onFailure { e ->
                YLog.error("Cache parse failed for ${metadata.id}: ${e.message}", e = e)
            }.getOrElse {
                songFromCache(metadata, null)
            }
        }

        private fun songFromCache(metadata: Metadata?, cache: LocalLyricCache?): Song {
            if (metadata == null) {
                return Song(id = cache?.musicId?.toString().orEmpty())
            }
            val parsed = cache?.takeUnless { it.pureMusic }?.toSong()
            if (parsed != null && !parsed.lyrics.isNullOrEmpty()) return parsed
            return Song(
                id = metadata.id.toString(),
                name = metadata.title,
                artist = metadata.artist,
                duration = metadata.duration
            )
        }

        private fun commitSong(song: Song, requestedTrack: PlaybackTrackToken) {
            val currentTrack = bridgeTrack
            if (!CloudMusicPlaybackPolicy.acceptsDownload(
                    playbackCommitter.currentTrack(),
                    requestedTrack,
                    song.id
                ) || currentTrack.token != requestedTrack
            ) {
                ProviderDiagnostics.debug(TAG) {
                    "Skip stale song commit, responseId=${song.id.orEmpty()}, " +
                        "requestedGeneration=${requestedTrack.generation}, " +
                        "currentGeneration=${currentTrack.generation}"
                }
                return
            }
            if (lastSetSong == song && lastSetTrack == requestedTrack) {
                sendCurrentBridgeSong(song)
                return
            }

            when (val result = playbackCommitter.commit(
                requestedTrack = requestedTrack,
                responseMediaId = song.id,
                duration = song.duration.takeIf { it > 0L } ?: currentTrack.duration,
                setSong = { lyricProvider?.player?.setSong(song) },
                setPosition = { lyricProvider?.player?.setPosition(it) },
                replayPlaybackState = { lyricProvider?.player?.setPlaybackState(it) },
                publishLyricReady = { sendCurrentBridgeSong(song) },
                publishPlaybackState = { state ->
                    sendBridgePlaybackState(currentTrack, state, force = true)
                }
            )) {
                is PlaybackStateCommitter.PlaybackCommitResult.Committed -> {
                    lastSetSong = song
                    lastSetTrack = requestedTrack
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

        private fun sendCurrentBridgeSong(song: Song?) {
            val currentTrack = bridgeTrack
            if (song?.id == currentTrack.mediaId && currentTrack.generation > 0L) {
                lastBridgeSong = song
                SaltLyricBridge.send(
                    context = appContext,
                    song = song,
                    lyricMode = translationType,
                    trackGeneration = currentTrack.generation
                )
            } else if (!song?.id.isNullOrBlank()) {
                ProviderDiagnostics.debug(TAG) {
                    "Skip stale Bridge lyric, responseId=${song.id}, " +
                        "currentId=${currentTrack.mediaId}, generation=${currentTrack.generation}"
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

        private fun runOnMainSafely(label: String, action: () -> Unit) {
            runOnMain {
                runCatching { action() }
                    .onFailure { error ->
                        ProviderDiagnostics.debug(TAG) {
                            "$label ignored: ${error.javaClass.simpleName}"
                        }
                    }
            }
        }
    }
}
