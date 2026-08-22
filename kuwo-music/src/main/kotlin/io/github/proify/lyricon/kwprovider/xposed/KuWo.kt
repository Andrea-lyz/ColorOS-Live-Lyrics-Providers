/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kwprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.proify.extensions.android.AndroidUtils
import io.github.proify.extensions.toRichLyricLines
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.kwprovider.BuildConfig
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.lang.reflect.Method
import java.lang.ref.WeakReference

/**
 * KuWo 歌词 Provider。
 *
 * 核心链路（Provider-only，不触碰字幕/翻译显示逻辑）：
 * hook cn.kuwo.mod.lyrics.e0.f(Music, boolean, Music) 的 after：
 *   -> LyricsInfo.lyricsData（明文 LRC/LRCX）
 *   -> 串歌校验（请求时的 Music 与当前曲目比对）
 *   -> LRC/LRCX 解析 -> Song -> Lyricon + KuWo 原生 MediaSession lyricInfo
 */
open class KuWo(val tag: String = "KuWoProvider") : YukiBaseHooker() {
    @Volatile
    private var lyriconProvider: LyriconProvider? = null

    @Volatile
    private var latestLyriconSong: Song? = null

    @Volatile
    private var latestPlaybackState: PlaybackState? = null

    @Volatile
    private var currentTrackKey: String? = null

    @Volatile
    private var currentTrackIdentity: String? = null

    @Volatile
    private var currentTrackGeneration = 0L

    @Volatile
    private var lastEmittedSignature = ""

    @Volatile
    private var lastEmittedLineCount = 0

    @Volatile
    private var lastEmittedSongId: String? = null

    @Volatile
    private var lastEmittedGeneration = 0L

    @Volatile
    private var latestSessionRef: WeakReference<android.media.session.MediaSession>? = null

    @Volatile
    private var currentMediaId: String? = null

    @Volatile
    private var latestHostMetadata: MediaMetadata? = null

    @Volatile
    private var mainHandler: Handler? = null

    @Volatile
    private var resolvedLyricFetchMethod: Method? = null

    private val pendingLyrics = LinkedHashMap<String, Song>(16, 0.75f, true)

    override fun onHook() {
        AndroidUtils.openBluetoothA2dpOn(appClassLoader)
        YLog.debug(tag = tag, msg = "进程: $processName")
        mainHandler = createMainHandler()
        resolveKuWoInternals()
        if (processName == packageName) {
            onAppLifecycle {
                onCreate {
                    initLyriconProvider()
                }
                onTerminate {
                    destroyLyriconProvider()
                }
            }
        } else {
            YLog.debug(
                tag = tag,
                msg = "KuWo Lyricon provider skipped in secondary process: $processName"
            )
        }
        hookMediaSession()
        hookKuWoLyric()
    }

    private fun createMainHandler(): Handler? {
        return runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    private fun resolveKuWoInternals() {
        runCatching {
            KuWoDexKitResolver.resolve(appInfo.sourceDir, appClassLoader)
        }.onSuccess { targets ->
            resolvedLyricFetchMethod = targets?.lyricFetchMethod
            KuWoOfficialLrcxAdapter.setResolvedParserMethod(targets?.lrcxParserMethod)
            debugProbe(
                "KuWo DexKit resolved internals: lyric=" +
                    (targets?.lyricFetchMethod?.let(::describeMethod) ?: "fallback") +
                    " lrcx=" +
                    (targets?.lrcxParserMethod?.let(::describeMethod) ?: "fallback")
            )
        }.onFailure { throwable ->
            YLog.error(
                tag = tag,
                msg = "KuWo DexKit resolution failed, using known-name fallback: $throwable"
            )
        }
    }

    private fun describeMethod(method: Method): String =
        method.declaringClass.name + "#" + method.name

    private fun initLyriconProvider() {
        val context = appContext ?: return
        runCatching {
            LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = context.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).also { provider ->
                lyriconProvider = provider
                provider.player.setDisplayTranslation(true)
                provider.register()
                latestLyriconSong?.let(::publishLyriconSong)
                latestPlaybackState?.let(provider.player::setPlaybackState)
            }
        }.onSuccess {
            debugProbe("KuWo Lyricon provider registered in $processName")
        }.onFailure { throwable ->
            YLog.error(tag = tag, msg = "KuWo Lyricon provider registration failed: $throwable")
        }
    }

    private fun destroyLyriconProvider() {
        val provider = lyriconProvider ?: return
        lyriconProvider = null
        runCatching { provider.destroy() }
            .onFailure { throwable ->
                YLog.error(tag = tag, msg = "KuWo Lyricon provider destroy failed: $throwable")
            }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState
                    latestPlaybackState = state
                    runCatching { lyriconProvider?.player?.setPlaybackState(state) }
                        .onFailure { throwable ->
                            YLog.error(
                                tag = tag,
                                msg = "KuWo Lyricon playback update failed: $throwable"
                            )
                        }
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                before {
                    val metadata = args[0] as? MediaMetadata ?: return@before
                    val decision = KuWoLyricInfoPublisher.prepareHostMetadata(metadata)
                    args[0] = decision.metadata
                    (instance as? android.media.session.MediaSession)?.let { session ->
                        latestSessionRef = WeakReference(session)
                        latestHostMetadata = decision.metadata
                    }
                }
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    if (!KuWoLyricInfoPublisher.onHostMetadataApplied()) {
                        return@after
                    }
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    currentMediaId = mediaId
                    val trackKey = buildTrackKey(title, artist)
                    val stableId = if (trackKey.isNotBlank()) {
                        trackKey
                    } else if (!mediaId.isNullOrBlank()) {
                        "media:" + mediaId
                    } else {
                        null
                    }
                    val trackIdentity = stableId?.let { buildTrackIdentity(mediaId, it) }
                    if (stableId != null && trackIdentity != currentTrackIdentity) {
                        currentTrackKey = stableId
                        currentTrackIdentity = trackIdentity
                        currentTrackGeneration = maxOf(
                            currentTrackGeneration + 1L,
                            SystemClock.elapsedRealtime()
                        )
                        KuWoLyricInfoPublisher.onTrackChanged(currentTrackGeneration)
                        lastEmittedSignature = ""
                        lastEmittedLineCount = 0
                        lastEmittedSongId = null
                        lastEmittedGeneration = 0L
                        publishLyriconSong(
                            Song(
                                id = mediaId.orEmpty(),
                                name = title,
                                artist = artist
                            )
                        )
                        emitPendingLyrics(stableId, mediaId)
                    }
                }
            }
        }
    }

    private fun hookKuWoLyric() {
        val method = findLyricFetchMethod()
        if (method == null) {
            YLog.error(tag = tag, msg = "Failed to find KuWo lyric fetch method")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                val music = param.args.getOrNull(0)
                handleLyricsInfo(result, music)
            }
        })
        debugProbe("Hooked KuWo lyric fetch: ${describeMethod(method)}")
    }

    /**
     * 字面名优先；后续混淆名变化时在此追加 DexKit 结构兜底。
     */
    private fun findLyricFetchMethod(): Method? {
        resolvedLyricFetchMethod?.let { return it }
        return runCatching {
            val clazz = appClassLoader?.loadClass("cn.kuwo.mod.lyrics.e0")
                ?: return null
            clazz.declaredMethods.firstOrNull { candidate ->
                candidate.name == "f" &&
                    candidate.parameterCount == 3 &&
                    candidate.parameterTypes[0].name == "cn.kuwo.base.bean.Music"
            }?.apply { isAccessible = true }
        }.getOrNull()
    }

    private fun handleLyricsInfo(lyricsInfo: Any, requestMusic: Any?) {
        val info = LyricsInfoReader.read(lyricsInfo) ?: return
        if (!info.isAvailable) return
        val lyricsData = info.lyricsData ?: return
        if (lyricsData.isBlank()) return

        val requestRid = requestMusic?.let { readMusicString(it, "getRid") }
        val requestKey = requestMusic?.let { readMusicTrackKey(it) }
        val song = buildSong(requestMusic, lyricsData, info.lyricsType, info.offset)
        if (song == null || song.lyrics.isNullOrEmpty()) return
        if (info.lyricsType.contains("LRCX", ignoreCase = true) &&
            song.lyrics.orEmpty().any { line ->
                val words = line.words
                !words.isNullOrEmpty() && words.all { it.end <= it.begin }
            }
        ) {
            logInvalidLrcxSourceSample(info.lyricsData)
        }
        cachePendingSong(song, requestRid, requestKey)

        val currentTrackKeyValue = currentTrackKey
        val currentMediaIdValue = currentMediaId
        val ridMismatch = !currentMediaIdValue.isNullOrBlank() &&
            !requestRid.isNullOrBlank() &&
            requestRid != currentMediaIdValue
        val keyMismatch = currentTrackKeyValue != null &&
            requestKey != null &&
            requestKey != currentTrackKeyValue
        if (ridMismatch || keyMismatch) {
            YLog.debug(
                tag = tag,
                msg = "KuWo lyric cached pending: rid=$requestRid key=$requestKey"
                    + " currentMediaId=$currentMediaIdValue currentKey=$currentTrackKeyValue"
            )
            return
        }
        emitSong(song, info.lyricsType)
    }


    private fun cachePendingSong(song: Song, rid: String?, trackKey: String?) {
        val keys = linkedSetOf<String>()
        if (!rid.isNullOrBlank()) keys.add("rid:" + rid)
        if (!trackKey.isNullOrBlank()) keys.add("key:" + trackKey)
        if (!song.id.isNullOrBlank()) keys.add("rid:" + song.id)
        val builtKey = buildTrackKey(song.name, song.artist)
        if (builtKey.isNotBlank()) keys.add("key:" + builtKey)
        if (keys.isEmpty()) return
        synchronized(pendingLyrics) {
            keys.forEach { pendingLyrics[it] = song }
            while (pendingLyrics.size > 16) {
                val oldest = pendingLyrics.keys.first()
                pendingLyrics.remove(oldest)
            }
        }
    }

    private fun emitPendingLyrics(trackKey: String?, mediaId: String?) {
        val candidates = mutableListOf<String>()
        if (!trackKey.isNullOrBlank()) candidates.add("key:" + trackKey)
        if (!mediaId.isNullOrBlank()) {
            candidates.add("rid:" + mediaId)
            candidates.add("key:" + mediaId)
        }
        val song = synchronized(pendingLyrics) {
            candidates.firstNotNullOfOrNull { pendingLyrics[it] }
        } ?: return
        emitSong(song)
    }

    private fun emitSong(song: Song, lyricsType: String? = null) {
        val generation = currentTrackGeneration
        val songId = song.id.orEmpty()
        val signature = buildSignature(song)
        if (songId == lastEmittedSongId &&
            generation == lastEmittedGeneration &&
            signature == lastEmittedSignature) {
            return
        }
        if (songId == lastEmittedSongId &&
            generation == lastEmittedGeneration &&
            lastEmittedLineCount > 0 &&
            (song.lyrics?.size ?: 0) < lastEmittedLineCount) {
            YLog.debug(
                tag = tag,
                msg = "KuWo lyric skipped: shorter payload lines=" + (song.lyrics?.size ?: 0) +
                    " previous=$lastEmittedLineCount"
            )
            return
        }
        lastEmittedSignature = signature
        lastEmittedLineCount = song.lyrics?.size ?: 0
        lastEmittedSongId = songId
        lastEmittedGeneration = generation
        publishLyriconSong(song)
        KuWoLyricInfoPublisher.onLyricReady(song, generation)
        scheduleImmediateLyricPublish(generation)
        logLyricTimingSample(song)
        debugProbe(
            "KuWo lyric ready: lines=" + (song.lyrics?.size ?: 0) +
                if (lyricsType.isNullOrBlank()) "" else (" type=" + lyricsType)
        )
    }

    private fun scheduleImmediateLyricPublish(generation: Long) {
        if (latestSessionRef?.get() == null || latestHostMetadata == null) return
        val handler = mainHandler
        if (handler == null) {
            YLog.debug(
                tag = tag,
                msg = "KuWo lyric publish skipped: main handler unavailable"
            )
            return
        }
        handler.postDelayed({
            if (generation != currentTrackGeneration) return@postDelayed
            val target = latestSessionRef?.get()
            val hostMetadata = latestHostMetadata
            if (target == null || hostMetadata == null) return@postDelayed
            try {
                debugProbe("KuWo lyric immediate publish gen=$generation")
                target.setMetadata(hostMetadata)
            } catch (throwable: Throwable) {
                YLog.error(
                    tag = tag,
                    msg = "KuWo lyric immediate publish failed: $throwable"
                )
            }
        }, 80L)
    }

    private fun logLyricTimingSample(song: Song) {
        if (!BuildConfig.DEBUG) return
        val lines = song.lyrics.orEmpty().take(3)
        lines.forEachIndexed { lineIndex, line ->
            val words = line.words.orEmpty().take(6).joinToString(separator=", ") { word ->
                "${word.begin}-${word.end}:${word.text}"
            }
            YLog.debug(
                tag = tag,
                msg = "KuWo lyric timing sample line=$lineIndex" +
                    " line=${line.begin}-${line.end}" +
                    " text=${line.text} words=[$words]"
            )
        }
    }

    private fun logInvalidLrcxSourceSample(raw: String?) {
        if (!BuildConfig.DEBUG) return
        val lines = raw.orEmpty().lineSequence().toList()
        val headers = lines.takeWhile { !it.startsWith("[00") && !it.startsWith("[01") }
            .take(12)
        val timed = lines.drop(headers.size).take(8)
        YLog.debug(
            tag = tag,
            msg = "KuWo LRCX invalid word-span source sample" +
                " rawChars=${raw?.length ?: 0}" +
                " headers=${headers.joinToString(separator=" ⏎ ")}" +
                " timed=${timed.joinToString(separator=" ⏎ ")}"
        )
    }

    private fun publishLyriconSong(song: Song) {
        val lyriconSong = KuWoLyriconSongNormalizer.normalize(song)
        latestLyriconSong = lyriconSong
        val provider = lyriconProvider
        if (provider == null) {
            if (!lyriconSong.lyrics.isNullOrEmpty()) {
                YLog.debug(tag = tag, msg = "KuWo Lyricon song cached until provider registration")
            }
            return
        }
        runCatching { provider.player.setSong(lyriconSong) }
            .onSuccess {
                val lines = lyriconSong.lyrics.orEmpty()
                val lineCount = lines.size
                if (lineCount > 0) {
                    val first = lines.first()
                    debugProbe(
                        "KuWo Lyricon song published in $processName: " +
                            "id=${lyriconSong.id.orEmpty()} lines=$lineCount " +
                            "firstSpan=${first.begin}-${first.end}"
                    )
                }
            }
            .onFailure { throwable ->
                YLog.error(tag = tag, msg = "KuWo Lyricon song publish failed: $throwable")
            }
    }

    private fun debugProbe(message: String) {
        if (BuildConfig.DEBUG) {
            YLog.debug(tag = tag, msg = message)
        }
    }

    private fun buildSong(
        requestMusic: Any?,
        lyricsData: String,
        lyricsType: String,
        offset: Int
    ): Song? {
        val music = requestMusic
        val title = music?.let { readMusicString(it, "getName") }
        val artist = music?.let { readMusicString(it, "getArtist") }
        val id = music?.let { readMusicString(it, "getRid") } ?: ""

        val officialParsed: List<RichLyricLine>? = if (lyricsType.contains("LRCX")) {
            KuWoOfficialLrcxAdapter.parse(appClassLoader, lyricsData)
        } else {
            null
        }
        val parsed: List<RichLyricLine> = when {
            officialParsed != null -> officialParsed
            lyricsType.contains("LRCX") -> KuWoLrcxParser.parse(lyricsData)
            else -> LrcParser.parse(lyricsData).lines.toRichLyricLines()
        }
        val lines = if (lyricsType.contains("LRCX")) {
            parsed
        } else {
            KuWoLrcxParser.attachTranslations(parsed)
        }
        if (lines.isEmpty()) return null

        val adjusted = if (offset != 0) {
            lines.map { line ->
                val begin = (line.begin - offset).coerceAtLeast(0L)
                val end = (line.end - offset).coerceAtLeast(begin)
                val adjustedWords = line.words.orEmpty().map { word ->
                    val wb = (word.begin - offset).coerceAtLeast(begin)
                    val we = (word.end - offset).coerceAtLeast(wb)
                    LyricWord(
                        begin = wb,
                        end = we,
                        duration = (we - wb).coerceAtLeast(0L),
                        text = word.text
                    )
                }
                RichLyricLine(
                    begin = begin,
                    end = end,
                    duration = (end - begin).coerceAtLeast(0L),
                    text = line.text,
                    words = adjustedWords,
                    translation = line.translation
                )
            }
        } else {
            lines
        }
        val duration = readMusicDuration(requestMusic)
        return Song(
            id = id,
            name = title,
            artist = artist,
            duration = duration,
            lyrics = adjusted
        )
    }

    private fun readMusicTrackKey(music: Any): String? {
        val name = readMusicString(music, "getName")
        val artist = readMusicString(music, "getArtist")
        return buildTrackKey(name, artist)
    }

    private fun readMusicDuration(music: Any?): Long {
        if (music == null) return 0L
        return runCatching {
            val field = music.javaClass.getField("duration")
            val value = field.get(music)
            if (value is Long) value else (value as? Int)?.toLong() ?: 0L
        }.getOrDefault(0L)
    }

    private fun readMusicString(music: Any, methodName: String): String? {
        return runCatching {
            val method = music.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            val fromMethod = method?.invoke(music) as? String
            if (!fromMethod.isNullOrBlank()) {
                fromMethod
            } else {
                // KuWo Music exposes public fields (name/artist/rid); getters are incomplete.
                val fieldName = when (methodName) {
                    "getName" -> "name"
                    "getArtist" -> "artist"
                    "getRid" -> "rid"
                    else -> null
                }
                if (fieldName != null) {
                    music.javaClass.getField(fieldName).get(music)?.toString()
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun buildSignature(song: Song): String {
        val first = song.lyrics?.firstOrNull()
        return song.id.orEmpty() + "|" +
            (song.lyrics?.size ?: 0) + "|" +
            first?.begin + "|" + first?.text
    }

    private fun buildTrackKey(title: String?, artist: String?): String =
        io.github.proify.extensions.bridge.TrackKeyBuilder.build(title, artist)

    private fun buildTrackIdentity(mediaId: String?, trackKey: String): String =
        if (!mediaId.isNullOrBlank()) {
            "media:" + mediaId
        } else {
            "key:" + trackKey
        }

    /**
     * 反射读取 LyricsDefine$LyricsInfo 的字段。
     */
    private object LyricsInfoReader {
        data class LyricsInfo(
            val lyricsData: String?,
            val lyricsType: String,
            val offset: Int,
            val isAvailable: Boolean
        )

        fun read(lyricsInfo: Any): LyricsInfo? {
            return runCatching {
                val clazz = lyricsInfo.javaClass
                val data = clazz.getField("lyricsData").get(lyricsInfo) as? String
                val type = clazz.getField("lyricsType").get(lyricsInfo)?.toString() ?: "LRC"
                val offset = (clazz.getField("offset").get(lyricsInfo) as? Int) ?: 0
                val available = runCatching {
                    clazz.getMethod("isAvailable").invoke(lyricsInfo) as? Boolean ?: false
                }.getOrDefault(false)
                LyricsInfo(data, type, offset, available)
            }.getOrNull()
        }
    }
}
