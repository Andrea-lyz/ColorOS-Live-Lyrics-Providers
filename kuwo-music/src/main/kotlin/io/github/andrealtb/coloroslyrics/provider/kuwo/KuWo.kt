/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookContext
import io.github.proify.extensions.android.AndroidUtils
import io.github.proify.extensions.toRichLyricLines
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.Song
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * KuWo 歌词 Provider。
 *
 * hook cn.kuwo.mod.lyrics.e0.f(Music, boolean, Music) 的 after：
 *   -> LyricsInfo.lyricsData（明文 LRC/LRCX）
 *   -> 串歌校验（请求时的 Music 与当前曲目比对）
 *   -> LRC/LRCX 解析 -> Song -> 酷我原生 MediaSession lyricInfo
 */
class KuWo(private val hookContext: ProviderHookContext) {
    private val hookRuntime = hookContext.runtime

    // v4.1: explicit ProviderHookContext accessors replace the Yuki global context properties.
    // Original names are kept so the business code below stays untouched.
    private val appClassLoader: ClassLoader
        get() = hookContext.classLoader
    private val processName: String
        get() = hookContext.processName

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
    private var latestSession: android.media.session.MediaSession? = null

    @Volatile
    private var currentMediaId: String? = null

    @Volatile
    private var latestHostMetadata: MediaMetadata? = null

    @Volatile
    private var mainHandler: Handler? = null

    @Volatile
    private var resolvedLyricFetchMethod: Method? = null

    private val pendingLyrics = LinkedHashMap<String, Song>(16, 0.75f, true)

    private val lyricRetryPolicy = KuWoLyricFetchRetryPolicy()

    private val lyricRetryExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "KuWoLyricRetry").apply { isDaemon = true }
        }

    private val artworkFetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KuWoArtworkFetch").apply { isDaemon = true }
    }

    private val artworkFetchLock = Any()

    @Volatile
    private var pendingArtworkFetchKey = ""

    @Volatile
    private var lastFetchInvoke: KuWoLyricFetchRetryPolicy.FetchInvoke? = null

    @Volatile
    private var debugConfigAnnounced = false

    fun onHook() {
        if (!applyRuntimeAndDebug()) {
            return
        }

        installBluetoothStateOverrides()
        KuWoDiagnostics.info(
            area = "bootstrap",
            event = "PROCESS_READY",
            process = processName
        )
        mainHandler = createMainHandler()
        resolveKuWoInternals()
        installApplicationLifecycleHooks()
        hookMediaSession()
        hookKuWoLyric()
    }

    private fun applyRuntimeAndDebug(): Boolean {
        RuntimeModeResolver.notifyXposedHookActive()
        val hostContext = hookContext.application
        val resolution = RuntimeModeResolver.resolve(hostContext)
        if (!resolution.mode.isSupported) {
            KuWoDiagnostics.warn(
                area = "bootstrap",
                event = "HOOK_DISABLED",
                process = resolution.processName,
                reason = resolution.markerSource,
                mode = resolution.mode
            )
            return false
        }
        val debug = ProviderDebugConfig.applyDiagnostics(
            mode = resolution.mode,
            provider = ProviderId.KUWO,
            rootSource = hookContext.debugSource,
            frameworkSink = hookContext.frameworkSink
        )
        if (debugConfigAnnounced) {
            return true
        }
        debugConfigAnnounced = true
        KuWoDiagnostics.info(
            area = "bootstrap",
            event = "DEBUG_CONFIG_APPLIED",
            process = processName,
            reason = debug.reason,
            mode = resolution.mode
        )
        if (debug.enabled) {
            KuWoDiagnostics.debug(
                area = "bootstrap",
                event = "DEBUG_LOGGING_ENABLED",
                process = processName,
                reason = debug.reason,
                mode = resolution.mode
            )
        }
        return true
    }

    private fun createMainHandler(): Handler? {
        return runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    private fun resolveKuWoInternals() {
        runCatching {
            KuWoDexKitResolver.resolve(hookContext.application.applicationInfo.sourceDir, appClassLoader)
        }.onSuccess { targets ->
            resolvedLyricFetchMethod = targets?.lyricFetchMethod
            KuWoOfficialLrcxAdapter.setResolvedParserMethod(targets?.lrcxParserMethod)
            KuWoDiagnostics.debug(
                area = "dexkit",
                event = "INTERNALS_RESOLVED",
                process = processName,
                message = "lyric=" +
                    (targets?.lyricFetchMethod?.let(::describeMethod) ?: "fallback") +
                    " lrcx=" +
                    (targets?.lrcxParserMethod?.let(::describeMethod) ?: "fallback")
            )
        }.onFailure { throwable ->
            KuWoDiagnostics.error(
                area = "dexkit",
                event = "INTERNALS_FALLBACK",
                process = processName,
                message = "KuWo DexKit resolution failed, using known-name fallback",
                throwable = throwable
            )
        }
    }

    private fun describeMethod(method: Method): String =
        method.declaringClass.name + "#" + method.name

    private fun hookMediaSession() {
        runCatching {
            val setMetadata = android.media.session.MediaSession::class.java
                .getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            hookRuntime.hook(setMetadata, "kuwo.session.MediaSession#setMetadata") {
                before {
                    val metadata = args.getOrNull(0) as? MediaMetadata ?: return@before
                    val decision = KuWoLyricInfoPublisher.prepareHostMetadata(metadata)
                    args[0] = decision.metadata
                    (instanceOrNull as? android.media.session.MediaSession)?.let { session ->
                        // The remote session and its callback stub do not keep this Java wrapper
                        // alive for us. Provider owns one current-session binding for its process.
                        latestSession = session
                        latestHostMetadata = decision.metadata
                    }
                }
                after {
                    val metadata = args.getOrNull(0) as? MediaMetadata ?: return@after
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
                        scheduleLyricFetchRetry(
                            lyricRetryPolicy.noteTrackChanged(stableId, mediaId),
                            "track-changed"
                        )
                        lastEmittedSignature = ""
                        lastEmittedLineCount = 0
                        lastEmittedSongId = null
                        lastEmittedGeneration = 0L
                        emitPendingLyrics(stableId, mediaId)
                    }
                }
            }
        }.onFailure { throwable ->
            KuWoDiagnostics.error(
                area = "session",
                event = "SESSION_HOOK_FAILED",
                process = processName,
                throwable = throwable
            )
        }
    }

    private fun hookKuWoLyric() {
        val method = findLyricFetchMethod()
        if (method == null) {
            KuWoDiagnostics.error(
                area = "hook",
                event = "LYRIC_FETCH_METHOD_MISSING",
                process = processName
            )
            return
        }
        hookRuntime.hook(method, "kuwo.lyrics.${method.declaringClass.name}#${method.name}") {
            after {
                runCatching {
                    val music = args.getOrNull(0)
                    val fetchCall = KuWoLyricFetchRetryPolicy.FetchCall(
                        rid = music?.let { readMusicString(it, "getRid") },
                        trackKey = music?.let { readMusicTrackKey(it) }
                    )
                    lastFetchInvoke = KuWoLyricFetchRetryPolicy.FetchInvoke(
                        method = method,
                        instance = instanceOrNull,
                        args = args.copyOf(),
                        call = fetchCall
                    )
                    val fetchResult = result
                    if (fetchResult != null && isAvailableLyricsInfo(fetchResult)) {
                        lyricRetryPolicy.noteFetchSucceeded(fetchCall)
                        handleLyricsInfo(fetchResult, music)
                        return@runCatching
                    }
                    scheduleLyricFetchRetry(
                        lyricRetryPolicy.noteFetchFailed(fetchCall),
                        "fetch-unavailable"
                    )
                }.onFailure { throwable ->
                    KuWoDiagnostics.error(
                        area = "hook",
                        event = "LYRIC_FETCH_CALLBACK_FAILED",
                        process = processName,
                        throwable = throwable
                    )
                }
            }
        }
        KuWoDiagnostics.debug(
            area = "hook",
            event = "LYRIC_FETCH_HOOKED",
            process = processName,
            message = describeMethod(method)
        )
    }

    /**
     * v4.1 replacement for AndroidUtils.openBluetoothA2dpOn: force the two Bluetooth state
     * getters to true through the API 102 runtime (equivalent to the 4.0 constant replacement).
     */
    private fun installBluetoothStateOverrides() {
        val overrides = AndroidUtils.findBluetoothA2dpOverrides(appClassLoader)
        if (overrides == null) {
            KuWoDiagnostics.debug(
                area = "bootstrap",
                event = "BLUETOOTH_OVERRIDE_SKIPPED",
                process = processName,
                reason = "lookup-failed"
            )
            return
        }
        val (isBluetoothA2dpOn, isAdapterEnabled) = overrides
        hookRuntime.hook(isBluetoothA2dpOn, "kuwo.bluetooth.AudioManager#isBluetoothA2dpOn") {
            before { result = true }
        }
        hookRuntime.hook(isAdapterEnabled, "kuwo.bluetooth.BluetoothAdapter#isEnabled") {
            before { result = true }
        }
    }

    /**
     * Replays the 4.0 onAppLifecycle behavior: re-apply runtime/debug on Application.onCreate
     * and shut the lyric retry executor down on Application.onTerminate.
     */
    private fun installApplicationLifecycleHooks() {
        runCatching {
            val applicationClass = Application::class.java
            hookRuntime.hook(
                applicationClass.getDeclaredMethod("onCreate"),
                "kuwo.app.Application#onCreate"
            ) {
                after { applyRuntimeAndDebug() }
            }
            hookRuntime.hook(
                applicationClass.getDeclaredMethod("onTerminate"),
                "kuwo.app.Application#onTerminate"
            ) {
                after { runCatching { lyricRetryExecutor.shutdownNow() } }
            }
        }.onFailure { throwable ->
            KuWoDiagnostics.error(
                area = "bootstrap",
                event = "APP_LIFECYCLE_HOOK_FAILED",
                process = processName,
                throwable = throwable
            )
        }
    }

    /**
     * 字面名优先；后续混淆名变化时在此追加 DexKit 结构兜底。
     */
    private fun findLyricFetchMethod(): Method? {
        resolvedLyricFetchMethod?.let { return it }
        return runCatching {
            val clazz = appClassLoader.loadClass("cn.kuwo.mod.lyrics.e0")
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
            KuWoDiagnostics.debug(
                area = "lyrics",
                event = "LYRIC_CACHED_PENDING",
                process = processName,
                message = "rid=$requestRid key=$requestKey" +
                    " currentMediaId=$currentMediaIdValue currentKey=$currentTrackKeyValue"
            )
            return
        }
        emitSong(song, info.lyricsType)
    }


    private fun isAvailableLyricsInfo(lyricsInfo: Any): Boolean {
        val info = KuWo.LyricsInfoReader.read(lyricsInfo) ?: return false
        return info.isAvailable && !info.lyricsData.isNullOrBlank()
    }

    private fun scheduleLyricFetchRetry(delayMs: Long?, reason: String) {
        if (delayMs == null) return
        KuWoDiagnostics.debug(
            area = "lyrics",
            event = "FETCH_RETRY_SCHEDULED",
            process = processName,
            reason = reason,
            message = "attempt=" + lyricRetryPolicy.pendingAttempts() + " delay=${delayMs}ms"
        )
        lyricRetryExecutor.schedule(
            ::runLyricFetchRetry,
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun runLyricFetchRetry() {
        val call = lyricRetryPolicy.takeRetryCall() ?: return
        val invoke = lastFetchInvoke ?: return
        if (invoke.call != call) return
        val result = runCatching {
            invoke.method.invoke(invoke.instance, *invoke.args)
        }.getOrNull()
        if (result != null && isAvailableLyricsInfo(result)) {
            KuWoDiagnostics.debug(
                area = "lyrics",
                event = "FETCH_RETRY_SUCCEEDED",
                process = processName,
                message = "attempt=" + lyricRetryPolicy.pendingAttempts()
            )
            lyricRetryPolicy.noteFetchSucceeded(call)
            handleLyricsInfo(result, invoke.args.getOrNull(0))
            return
        }
        scheduleLyricFetchRetry(
            lyricRetryPolicy.noteFetchFailed(call),
            "retry-unavailable"
        )
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
            KuWoDiagnostics.debug(
                area = "lyrics",
                event = "SHORTER_PAYLOAD_SKIPPED",
                process = processName,
                message = "lines=" + (song.lyrics?.size ?: 0) + " previous=$lastEmittedLineCount"
            )
            return
        }
        lastEmittedSignature = signature
        if (!song.lyrics.isNullOrEmpty()) {
            lyricRetryPolicy.noteLyricsEmitted(
                songId,
                buildTrackKey(song.name, song.artist)
            )
        }
        lastEmittedLineCount = song.lyrics?.size ?: 0
        lastEmittedSongId = songId
        lastEmittedGeneration = generation
        KuWoLyricInfoPublisher.onLyricReady(song, generation)
        scheduleImmediateLyricPublish(generation)
        logLyricTimingSample(song)
        KuWoDiagnostics.debug(
            area = "lyrics",
            event = "LYRIC_READY",
            process = processName,
            message = "lines=" + (song.lyrics?.size ?: 0) +
                if (lyricsType.isNullOrBlank()) "" else (" type=" + lyricsType)
        )
    }

    private fun scheduleImmediateLyricPublish(generation: Long) {
        val session = latestSession
        val metadata = latestHostMetadata
        if (session == null || metadata == null) {
            KuWoDiagnostics.debug(
                area = "publish",
                event = "IMMEDIATE_PUBLISH_SKIPPED",
                process = processName,
                reason = if (session == null) "session-unavailable" else "metadata-unavailable"
            )
            return
        }
        val handler = mainHandler
        if (handler == null) {
            KuWoDiagnostics.debug(
                area = "publish",
                event = "IMMEDIATE_PUBLISH_SKIPPED",
                process = processName,
                reason = "main-handler-unavailable"
            )
            return
        }
        handler.postDelayed({
            if (generation != currentTrackGeneration) return@postDelayed
            val target = latestSession
            val hostMetadata = latestHostMetadata
            if (target == null || hostMetadata == null) return@postDelayed
            try {
                KuWoDiagnostics.debug(
                    area = "publish",
                    event = "IMMEDIATE_PUBLISH",
                    process = processName,
                    message = "gen=$generation"
                )
                target.setMetadata(hostMetadata)
            } catch (throwable: Throwable) {
                KuWoDiagnostics.error(
                    area = "publish",
                    event = "IMMEDIATE_PUBLISH_FAILED",
                    process = processName,
                    throwable = throwable
                )
            }
        }, 80L)
        scheduleArtworkCompletion(generation, metadata)
    }

    private fun scheduleArtworkCompletion(generation: Long, sourceMetadata: MediaMetadata) {
        if (sourceMetadata.hasKuWoArtworkBitmap()) return
        val fetchIdentity = currentTrackIdentity ?: return
        val uri = KUWO_ARTWORK_URI_KEYS.firstNotNullOfOrNull { key ->
            sourceMetadata.getString(key)?.takeIf { it.isNotBlank() }
        } ?: return
        val fetchKey = "$generation|${uri.hashCode()}"
        synchronized(artworkFetchLock) {
            if (pendingArtworkFetchKey == fetchKey) return
            pendingArtworkFetchKey = fetchKey
        }
        artworkFetchExecutor.execute {
            val fetched = runCatching { KuWoArtworkFetcher.fetch(sourceMetadata) }
            synchronized(artworkFetchLock) {
                if (pendingArtworkFetchKey == fetchKey) pendingArtworkFetchKey = ""
            }
            val bitmap = fetched.getOrNull()
            if (bitmap == null) {
                KuWoDiagnostics.debug(
                    area = "artwork",
                    event = "ARTWORK_COMPLETION_SKIPPED",
                    process = processName,
                    reason = fetched.exceptionOrNull()?.javaClass?.simpleName ?: "unavailable"
                )
                return@execute
            }
            if (generation != currentTrackGeneration
                || fetchIdentity != currentTrackIdentity
                || latestHostMetadata?.hasKuWoArtworkBitmap() == true) {
                bitmap.recycle()
                return@execute
            }
            val completedMetadata = KuWoArtworkFetcher.withArtworkBitmap(sourceMetadata, bitmap)
            val targetHandler = mainHandler ?: run {
                bitmap.recycle()
                return@execute
            }
            targetHandler.post {
                if (generation != currentTrackGeneration
                    || fetchIdentity != currentTrackIdentity
                    || latestHostMetadata?.hasKuWoArtworkBitmap() == true) {
                    bitmap.recycle()
                    return@post
                }
                val target = latestSession ?: run {
                    bitmap.recycle()
                    return@post
                }
                latestHostMetadata = completedMetadata
                runCatching { target.setMetadata(completedMetadata) }
                    .onSuccess {
                        KuWoDiagnostics.debug(
                            area = "artwork",
                            event = "ARTWORK_COMPLETION_PUBLISHED",
                            process = processName,
                            message = "gen=$generation size=${bitmap.width}x${bitmap.height}"
                        )
                    }
                    .onFailure { throwable ->
                        KuWoDiagnostics.error(
                            area = "artwork",
                            event = "ARTWORK_COMPLETION_FAILED",
                            process = processName,
                            throwable = throwable
                        )
                    }
            }
        }
    }

    private fun logLyricTimingSample(song: Song) {
        if (!StructuredDiagnostics.isDebugEnabled) return
        val lines = song.lyrics.orEmpty().take(3)
        lines.forEachIndexed { lineIndex, line ->
            val words = line.words.orEmpty().take(6).joinToString(separator=", ") { word ->
                "${word.begin}-${word.end}:${word.text}"
            }
            KuWoDiagnostics.debug(
                area = "lyrics",
                event = "TIMING_SAMPLE",
                process = processName,
                message = "line=$lineIndex" +
                    " span=${line.begin}-${line.end}" +
                    " text=${line.text} words=[$words]"
            )
        }
    }

    private fun logInvalidLrcxSourceSample(raw: String?) {
        if (!StructuredDiagnostics.isDebugEnabled) return
        val lines = raw.orEmpty().lineSequence().toList()
        val headers = lines.takeWhile { !it.startsWith("[00") && !it.startsWith("[01") }
            .take(12)
        val timed = lines.drop(headers.size).take(8)
        KuWoDiagnostics.debug(
            area = "parser",
            event = "LRCX_INVALID_WORD_SPAN",
            process = processName,
            message = "rawChars=${raw?.length ?: 0}" +
                " headers=${headers.joinToString(separator=" ⏎ ")}" +
                " timed=${timed.joinToString(separator=" ⏎ ")}"
        )
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
