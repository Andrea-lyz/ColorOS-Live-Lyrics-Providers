/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.kyant.taglib.TagLib
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.paprovider.bridge.BridgeConstants
import io.github.proify.lyricon.paprovider.bridge.Configs
import io.github.proify.lyricon.paprovider.xposed.util.SafUriResolver
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object PowerAmp : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "PowerAmpProvider"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
    private const val ACTION_TOGGLE_TRANSLATION =
        "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
    private const val TRANSLATION_ACTION_NAME = "翻译"

    // 匹配元数据key
    private val lyricTagRegex = Regex("(?i)\\b(LYRICS)\\b")
    private val translationAction by lazy(LazyThreadSafetyMode.PUBLICATION) {
        PlaybackState.CustomAction.Builder(
            ACTION_TOGGLE_TRANSLATION,
            TRANSLATION_ACTION_NAME,
            android.R.drawable.ic_menu_manage
        ).build()
    }
    private val playbackStateCustomActionsField by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findPlaybackStateCustomActionsField()
    }

    @SuppressLint("SoonBlockedPrivateApi", "PrivateApi")
    private fun findPlaybackStateCustomActionsField() =
        runCatching {
            PlaybackState.Builder::class.java.getDeclaredField("mCustomActions").apply {
                isAccessible = true
            }
        }.getOrNull()

    private var provider: LyriconProvider? = null
    private var trackReceiver: BroadcastReceiver? = null
    private var currentMetadata: TrackMetadata? = null
    @Volatile
    private var currentTrackGeneration: Long = System.currentTimeMillis()
    @Volatile
    private var currentTrackIdentity: String = ""
    private val pendingOnlineGenerations = mutableMapOf<String, Long>()
    private var translationActionInjectionLogged = false

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                initDataChannel()
                setupLyriconProvider(this)
                setupBroadcastReceiver(this)
            }
            onTerminate { release() }
        }
        hookMediaSession()
        PowerampLyricInfoPublisher.install()
        PowerampInternalProbe.install(appClassLoader)
    }

    private fun initDataChannel() {
        dataChannel.wait(key = BridgeConstants.ACTION_SETTING_CHANGED) {
            PowerampLog.debug(tag = TAG, msg = "Settings changed signal received")
            applyProviderSettings()
        }
    }

    private fun setupLyriconProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            register()
        }
        applyProviderSettings()
    }

    /**
     * 应用当前配置项。
     */
    private fun applyProviderSettings() {
        val isTranslationEnabled = prefs.get(Configs.ENABLE_TRANSLATION)
        provider?.player?.setDisplayTranslation(isTranslationEnabled)
        PowerampLog.debug(tag = TAG, msg = "Settings applied: translationEnabled=$isTranslationEnabled")
    }

    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter(ACTION_TRACK_CHANGED)
        trackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TRACK_CHANGED) {
                    handleTrackChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
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
                    before {
                        val state = args[0] as? PlaybackState ?: return@before
                        val patched = copyPlaybackStateWithTranslationActionFirst(state)
                        if (patched !== state) {
                            args[0] = patched
                            if (!translationActionInjectionLogged) {
                                translationActionInjectionLogged = true
                                PowerampLog.debug(
                                    tag = TAG,
                                    msg = "Injected translation toggle action into Poweramp PlaybackState"
                                )
                            }
                        }
                    }
                    after {
                        val state = args[0] as? PlaybackState ?: return@after
                        provider?.player?.setPlaybackState(state)
                    }
                }
            }
    }

    private fun copyPlaybackStateWithTranslationActionFirst(
        original: PlaybackState
    ): PlaybackState {
        val originalActions = original.customActions.orEmpty()
        if (originalActions.any { it.action == ACTION_TOGGLE_TRANSLATION }) return original

        val builder = PlaybackState.Builder(original)
        if (!clearPlaybackStateBuilderCustomActions(builder)) {
            builder.addCustomAction(translationAction)
            return builder.build()
        }
        builder.addCustomAction(translationAction)
        originalActions.forEach { action ->
            if (action.action != ACTION_TOGGLE_TRANSLATION) {
                builder.addCustomAction(action)
            }
        }
        return builder.build()
    }

    private fun clearPlaybackStateBuilderCustomActions(
        builder: PlaybackState.Builder
    ): Boolean = runCatching {
        val field = playbackStateCustomActionsField ?: return@runCatching false
        val actions = field.get(builder) as? MutableList<*> ?: return@runCatching false
        actions.clear()
        true
    }.getOrDefault(false)

    /**
     * 处理轨道变化逻辑。
     */
    private fun handleTrackChange(intent: Intent) {
        val bundle = intent.extras ?: return
        val metadata = TrackMetadataCache.save(bundle) ?: return

        val generation = beginTrack(metadata) ?: return

        updateTrack(metadata, generation)

        // 先发送基础歌曲信息（清除旧歌词）
        val hasLocalLyric = loadLyricsFromTrack(metadata, generation)

        // 尝试加载本地歌词

        // 本地加载失败则根据配置尝试网络搜索
        if (!hasLocalLyric) {
            val isNetSearchEnabled = prefs.get(Configs.ENABLE_NET_SEARCH)
            if (isNetSearchEnabled) {
                PowerampLog.debug(
                    tag = TAG,
                    msg = "Local lyric not found, triggering net search for: ${metadata.title}"
                )
                searchLyricsOnline(metadata, generation)
            } else {
                PowerampLog.debug(tag = TAG, msg = "Local lyric not found and net search is disabled")
            }
        }
    }

    /**
     * 从 URI 读取并解析本地歌词。
     */
    private fun beginTrack(metadata: TrackMetadata): Long? = synchronized(this) {
        val identity = metadata.identityKey()
        if (currentTrackIdentity == identity) return@synchronized null
        currentTrackGeneration = maxOf(currentTrackGeneration + 1L, System.currentTimeMillis())
        currentTrackIdentity = identity
        currentMetadata = metadata
        currentTrackGeneration
    }

    private fun updateTrack(metadata: TrackMetadata, generation: Long) {
        val song = Song(
            id = metadata.id,
            name = metadata.title,
            artist = metadata.artist,
            duration = metadata.duration
        )
        PowerampLog.debug(
            tag = TAG,
            msg = "Updating track: generation=$generation, id=${metadata.id}, title=${metadata.title}"
        )
        PowerampLyricInfoPublisher.onTrackChanged(metadata, generation)
        PowerampSaltLyricBridge.sendTrackChanged(appContext, metadata, generation)
        provider?.player?.setSong(song)
    }

    private fun loadLyricsFromTrack(metadata: TrackMetadata, generation: Long): Boolean {
        val rawPath = metadata.path ?: return false
        val formattedPath = formatSafPath(rawPath) ?: return false
        val uri = SafUriResolver.resolveToUri(appContext!!, formattedPath) ?: return false
        return loadLyricsFromUri(metadata, uri, generation)
    }

    private fun loadLyricsFromUri(data: TrackMetadata, uri: Uri, generation: Long): Boolean {
        val rawLyric = fetchLyricFromTag(uri) ?: return false

        val parsedLrc = EnhanceLrcParser.parse(rawLyric, data.duration).lines.filter {
            !it.text.isNullOrBlank()
        }

        val song = Song(
            id = data.id,
            name = data.title,
            artist = data.artist,
            duration = data.duration,
            lyrics = parsedLrc
        )

        updateSong(song, generation, rawLyric)
        PowerampLog.debug(tag = TAG, msg = "Local lyric loaded for: ${data.title}")
        return true
    }

    /**
     * 使用 TagLib 提取音频标签中的歌词字段。
     */
    private fun fetchLyricFromTag(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        PowerampLog.error(tag = TAG, msg = "Failed to fetch lyric tag from $uri", e = e)
        null
    }

    private fun searchLyricsOnline(metadata: TrackMetadata, generation: Long) {
        synchronized(pendingOnlineGenerations) {
            pendingOnlineGenerations[metadata.id] = generation
        }
        Downloader.search(metadata, this) {
            trackName = metadata.title
            artistName = metadata.artist
            albumName = metadata.album
        }
    }

    /**
     * 将 PowerAmp 路径格式转换为 SAF 兼容路径。
     * 例：`primary/Music/test.mp3` -> `primary:Music/test.mp3`
     */
    private fun formatSafPath(path: String): String? {
        val input = path.trimStart()
        if (input.isEmpty() || input.startsWith("/")) return null

        val separatorIndex = input.indexOf('/')
        if (separatorIndex == -1) return null

        val volumeId = input.take(separatorIndex)
        val relativePath = input.substring(separatorIndex + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    /**
     * 向歌词提供者更新当前歌曲信息。
     */
    private fun updateSong(
        song: Song?,
        generation: Long,
        bridgeSourceLyric: String? = null
    ) {
        val currentSong = song ?: return
        if (!isCurrentTrack(currentSong, generation)) {
            PowerampLog.debug(
                tag = TAG,
                msg = "Skip stale lyric update: generation=$generation, id=${currentSong.id}, title=${currentSong.name}"
            )
            return
        }
        PowerampLog.debug(
            tag = TAG,
            msg = "Updating song lyric: generation=$generation, id=${currentSong.id}, title=${currentSong.name}"
        )
        provider?.player?.setSong(currentSong)
        PowerampLyricInfoPublisher.onLyricReady(
            appContext,
            currentSong,
            generation,
            bridgeSourceLyric
        )
    }

    private fun isCurrentTrack(song: Song?, generation: Long): Boolean {
        if (song == null) return false
        val identity = trackIdentity(song.id, song.name, song.artist)
        return generation == currentTrackGeneration && identity == currentTrackIdentity
    }

    private fun isCurrentTrack(metadata: TrackMetadata, generation: Long): Boolean {
        return generation == currentTrackGeneration &&
            metadata.identityKey() == currentTrackIdentity
    }

    // --- 回调与生命周期释放 ---

    override fun onDownloadFinished(metadata: TrackMetadata, response: List<ProviderLyrics>) {
        val generation = synchronized(pendingOnlineGenerations) {
            pendingOnlineGenerations.remove(metadata.id)
        } ?: return
        if (!isCurrentTrack(metadata, generation)) {
            PowerampLog.debug(
                tag = TAG,
                msg = "Skip stale online lyric: generation=$generation, title=${metadata.title}"
            )
            return
        }

        val richLyrics = response.firstOrNull()?.lyrics?.rich
        val song = Song(
            id = metadata.id,
            name = metadata.title,
            artist = metadata.artist,
            duration = metadata.duration,
            lyrics = richLyrics
        )
        updateSong(song, generation)
        PowerampLog.debug(tag = TAG, msg = "Online lyric applied for: ${metadata.title}")
    }

    override fun onDownloadFailed(metadata: TrackMetadata, e: Exception) {
        synchronized(pendingOnlineGenerations) {
            pendingOnlineGenerations.remove(metadata.id)
        }
        PowerampLog.error(tag = TAG, msg = "Online lyric download failed for: ${metadata.title}", e = e)
    }

    private fun TrackMetadata.identityKey(): String = trackIdentity(id, title, artist)

    private fun trackIdentity(id: String?, title: String?, artist: String?): String {
        val stableId = id.orEmpty().trim()
        if (stableId.isNotEmpty()) return stableId
        return "${title.orEmpty().trim().lowercase()}|${artist.orEmpty().trim().lowercase()}"
    }

    private fun release() {
        trackReceiver?.let { appContext?.unregisterReceiver(it) }
        trackReceiver = null
        PowerampLog.debug(tag = TAG, msg = "PowerAmp provider released")
    }
}
