/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.media.session.MediaSession
import android.os.SystemClock
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.android.AndroidUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.proify.extensions.toRichLyricLines
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.kgprovider.xposed.Constants
import io.github.proify.lyricon.krckit.KrcDecryptor
import io.github.proify.lyricon.krckit.KrcParser
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.LinkedHashMap

abstract class KuGouBase : YukiBaseHooker() {

    companion object {
        protected const val TAG = "KuGouProvider"
        private const val KUGOU_DIAGNOSTICS_ENABLED = false
        private const val TRACK_CHANGED_DEBOUNCE_MS = 1_200L
        private const val NOISY_METADATA_IDENTITY_STABILIZE_MS = 280L
        private const val NOISY_LYRIC_CANDIDATE_GRACE_MS = 220L
        private const val LYRIC_FILE_READY_RETRY_COUNT = 5
        private const val LYRIC_FILE_READY_RETRY_DELAY_MS = 80L
        private const val LYRIC_CANDIDATE_BIND_WINDOW_MS = 1_500L
        private const val LYRIC_CANDIDATE_EARLY_WINDOW_MS = 500L
        private const val LYRIC_CANDIDATE_MAX_AGE_MS = 10_000L
        private val LOCAL_LYRIC_FILE_FAST_PROBE_DELAYS_MS = longArrayOf(0L, 220L, 760L, 1_600L)
        private const val LOCAL_LYRIC_FILE_FALLBACK_RETRY_COUNT = 3
        private const val LOCAL_LYRIC_FILE_FALLBACK_RETRY_INTERVAL_MS = 2_000L
        private const val MAX_PENDING_LYRIC_CANDIDATES = 8
        private const val PLAYBACK_STATE_THROTTLE_MS = 300L
        private const val PLAYBACK_STATE_TRACK_CHANGE_SUPPRESS_MS = 600L
        private const val MAX_ARTWORK_CACHE_ENTRIES = 16
        private const val ORIGINAL_LYRIC_SNAPSHOT_EXTRA = "kg_original_lyric_snapshot"
        private const val ORIGINAL_LYRIC_STARTED_EXTRA = "kg_original_lyric_started"
        private val KUGOU_HASH_SUFFIX_REGEX = Regex("[0-9a-fA-F]{16,}$")
        private val CAR_LYRIC_CREDIT_TOKEN_REGEX = Regex(
            "lyricist|composer|arranger|producer|produced\\s+by|vocal|harmony|backing vocal|" +
                "background vocal|recording|mixing|mastering|publisher|copyright|engineer|" +
                "studio|music copyist|conductor|orchestra|choir|piccolo|flute|harmonica|harp|" +
                "\\u4f5c\\u8bcd|\\u4f5c\\u66f2|\\u7f16\\u66f2|\\u5236\\u4f5c\\u4eba|" +
                "\\u6f14\\u5531|\\u4eba\\u58f0|\\u548c\\u58f0|\\u5f55\\u97f3|" +
                "\\u5f55\\u97f3\\u5e08|\\u6df7\\u97f3|\\u6bcd\\u5e26|\\u76d1\\u5236|" +
                "\\u51fa\\u54c1|\\u6307\\u6325|\\u4e50\\u961f|\\u7edf\\u7b79|\\u5f26\\u4e50|\\u5409\\u4ed6|" +
                "\\u8d1d\\u65af|\\u9f13|\\u952e\\u76d8",
            RegexOption.IGNORE_CASE
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()

    protected var provider: LyriconProvider? = null

    @Volatile
    protected var currentSongId: String? = null

    @Volatile
    private var currentTrackGeneration = 0L

    @Volatile
    private var currentMetadata: MetadataData? = null

    @Volatile
    private var currentTrackChangedAtElapsed = 0L

    private var lastEmittedSong: Song? = null
    private var lastEmittedSongGeneration = 0L
    private var lastEmittedSongSignature = ""
    private var lastLyricReadyGeneration = 0L
    private var pendingTrackChangedJob: Job? = null
    private var pendingMetadataIdentityJob: Job? = null
    private var pendingMetadataIdentity: MetadataData? = null
    private var pendingMetadataIdentityStartedAtElapsed = 0L
    private var lastArtworkIdentityId = ""
    private var lastArtworkMetadata: MediaMetadata? = null
    private val artworkMetadataCache =
        object : LinkedHashMap<String, MediaMetadata>(MAX_ARTWORK_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadata>?): Boolean {
                return size > MAX_ARTWORK_CACHE_ENTRIES
            }
        }
    private var pendingLyricCandidateBindJob: Job? = null
    private var pendingLocalLyricProbeJob: Job? = null
    private var pendingLocalLyricProbeGeneration = 0L
    private val pendingLyricCandidates = ArrayDeque<LyricCandidate>()
    private var isInitialized = false
    private var lastForwardedPlaybackStateCode = -1
    private var lastForwardedPlaybackStateElapsed = 0L
    private var trackChangeSuppressUntilElapsed = 0L

    protected lateinit var dexKitBridge: DexKitBridge
        private set

    private data class TrackSnapshot(
        val songId: String?,
        val generation: Long,
        val metadata: MetadataData?,
        val changedAtElapsed: Long
    )

    private data class LyricCandidate(
        val lyrics: List<RichLyricLine>,
        val capturedSongId: String?,
        val capturedGeneration: Long,
        val path: String,
        val startedAtElapsed: Long,
        val completedAtElapsed: Long
    )

    private data class LocalLyricQuery(
        val normalizedTitle: String,
        val normalizedArtist: String,
        val artistTitle: String,
        val titleArtist: String
    )

    private data class LocalLyricDirectory(
        val root: File,
        val label: String
    )

    init {
        System.loadLibrary("dexkit")
    }

    override fun onHook() {
        if (!shouldHookProcess()) return
        AndroidUtils.openBluetoothA2dpOn(appClassLoader)
        dexKitBridge = DexKitBridge.create(appInfo.sourceDir)
        hookMediaSession()
        scope.launch { asyncHookLyricManager() }

        onAppLifecycle {
            onCreate {
                initProvider()
                onAppCreate()
            }
        }
    }

    protected abstract fun onAppCreate()
    protected abstract fun shouldHookProcess(): Boolean
    protected open fun shouldStabilizeNoisyMetadataIdentity(): Boolean = false
    protected open fun shouldPublishMediaSessionLyricInfo(): Boolean = true
    protected open fun shouldUseCarLyricFallback(): Boolean = true
    protected open fun useOriginalApkLyricPipeline(): Boolean = false
    protected open fun shouldThrottleBridgePlaybackState(): Boolean = false

    private fun initProvider() {
        if (isInitialized) return
        val ctx = appContext ?: return

        try {
            provider = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = ctx.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).apply {
                register()
                player.setDisplayTranslation(true)
            }
            isInitialized = true
            YLog.info(msg = "Lyricon Provider initialized for ${ctx.packageName}", tag = TAG)
        } catch (e: Exception) {
            YLog.error(msg = "Failed to init provider: ${e.message}", tag = TAG)
        }
    }

    private fun asyncHookLyricManager() {
        fun findLoadLyricMethodFromDexKit(): Method? {
            val methodData = dexKitBridge
                .findClass {
                    matcher {
                        className = "com.kugou.framework.lyric.LyricManager"
                    }
                }
                .findMethod {
                    matcher {
                        addUsingString("file is not krc or lyc or txt file")
                        paramTypes(String::class.java, Boolean::class.javaPrimitiveType)
                    }
                }.singleOrNull()
            return methodData?.getMethodInstance(appClassLoader!!)
        }

        val method = findLoadLyricMethodFromDexKit()
        if (method == null) {
            YLog.error(tag = TAG, msg = "Failed to find KuGou LyricManager load method")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val path = lyricPathFromParam(param) ?: return
                if (useOriginalApkLyricPipeline()) {
                    param?.setObjectExtra(ORIGINAL_LYRIC_SNAPSHOT_EXTRA, currentTrackSnapshot())
                    param?.setObjectExtra(ORIGINAL_LYRIC_STARTED_EXTRA, SystemClock.elapsedRealtime())
                    return
                }
                processLyricFileAsync(path, "before-load")
            }

            override fun afterHookedMethod(param: MethodHookParam?) {
                val path = lyricPathFromParam(param) ?: return
                if (useOriginalApkLyricPipeline()) {
                    handleOriginalLyricLoadResult(param, path)
                    return
                }
                processLyricFileAsync(path, "after-load")
            }
        })
    }

    private fun lyricPathFromParam(param: XC_MethodHook.MethodHookParam?): String? {
        val args = param?.args ?: return null
        return args.getOrNull(0) as? String
    }

    private fun processLyricFileAsync(
        path: String,
        reason: String,
        capturedSnapshot: TrackSnapshot = currentTrackSnapshot(),
        startedAtElapsed: Long = SystemClock.elapsedRealtime()
    ) {
        scope.launch {
            try {
                val file = waitForReadableLyricFile(path) ?: run {
                    diagnoseDebug("KG_DIAG lyric file missing path=${path.takeLast(96)}")
                    return@launch
                }
                val lyrics = parseLyricFile(file)
                if (lyrics.isEmpty()) {
                    diagnoseDebug("KG_DIAG parsed empty lyric path=${path.takeLast(96)}")
                    return@launch
                }
                handleLyricCandidate(
                    LyricCandidate(
                        lyrics = lyrics,
                        capturedSongId = capturedSnapshot.songId,
                        capturedGeneration = capturedSnapshot.generation,
                        path = path,
                        startedAtElapsed = startedAtElapsed,
                        completedAtElapsed = SystemClock.elapsedRealtime()
                    ),
                    "parsed-$reason"
                )
            } catch (e: Exception) {
                YLog.error(tag = TAG, msg = "Lyric parsing failed: ${e.message}")
            }
        }
    }

    private fun handleOriginalLyricLoadResult(
        param: XC_MethodHook.MethodHookParam?,
        path: String
    ) {
        val capturedSnapshot =
            param?.getObjectExtra(ORIGINAL_LYRIC_SNAPSHOT_EXTRA) as? TrackSnapshot
                ?: currentTrackSnapshot()
        val startedAtElapsed =
            param?.getObjectExtra(ORIGINAL_LYRIC_STARTED_EXTRA) as? Long
                ?: SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val lyrics = parseOriginalLyricResult(param?.result)
                if (lyrics.isNotEmpty()) {
                    handleLyricCandidate(
                        LyricCandidate(
                            lyrics = lyrics,
                            capturedSongId = capturedSnapshot.songId,
                            capturedGeneration = capturedSnapshot.generation,
                            path = path,
                            startedAtElapsed = startedAtElapsed,
                            completedAtElapsed = SystemClock.elapsedRealtime()
                        ),
                        "original-lyric-result"
                    )
                    return@launch
                }
                processLyricFileAsync(
                    path = path,
                    reason = "original-file-fallback",
                    capturedSnapshot = capturedSnapshot,
                    startedAtElapsed = startedAtElapsed
                )
            } catch (e: Exception) {
                YLog.error(tag = TAG, msg = "Original lyric result handling failed: ${e.message}")
            }
        }
    }

    private suspend fun waitForReadableLyricFile(path: String): File? {
        val file = File(path)
        repeat(LYRIC_FILE_READY_RETRY_COUNT) { attempt ->
            if (file.exists() && file.length() > 0L) {
                return file
            }
            if (attempt < LYRIC_FILE_READY_RETRY_COUNT - 1) {
                delay(LYRIC_FILE_READY_RETRY_DELAY_MS)
            }
        }
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    private fun parseLyricFile(file: File): List<RichLyricLine> {
        return when (file.extension.lowercase()) {
            "krc" -> parseKrcBytes(file.readBytes())
            "lyc" -> parseKrcBytes(file.readBytes()).ifEmpty {
                parseLrcText(runCatching { file.readText() }.getOrDefault(""))
            }
            "lrc", "txt" -> parseLrcText(file.readText())
            else -> emptyList()
        }
    }

    private fun parseKrcBytes(raw: ByteArray): List<RichLyricLine> {
        return runCatching {
            val decrypted = KrcDecryptor.decrypt(raw)
            KrcParser.parse(decrypted).richLyricLines
        }.getOrDefault(emptyList())
    }

    private fun parseLrcText(raw: String): List<RichLyricLine> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            LrcParser.parse(raw).lines.toRichLyricLines()
        }.getOrDefault(emptyList())
    }

    private fun parseOriginalLyricResult(result: Any?): List<RichLyricLine> {
        val lyricData = originalLyricDataFromResult(result) ?: return emptyList()
        return runCatching {
            originalLyricDataToRichLines(lyricData)
        }.onFailure {
            diagnoseDebug("KG_ORIG parse LyricData failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun originalLyricDataFromResult(result: Any?): Any? {
        if (result == null) return null
        return runCatching {
            result.javaClass.declaredFields.firstOrNull {
                it.type.name == "com.kugou.framework.lyric.LyricData"
            }?.let {
                it.isAccessible = true
                it.get(result)
            }
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun originalLyricDataToRichLines(lyricData: Any): List<RichLyricLine> {
        val words = callOriginalLyricData<Array<Array<String>>>(lyricData, "getWords")
            ?: return emptyList()
        val rowBeginTime = callOriginalLyricData<LongArray>(lyricData, "getRowBeginTime")
            ?: return emptyList()
        val rowDelayTime = callOriginalLyricData<LongArray>(lyricData, "getRowDelayTime")
            ?: LongArray(rowBeginTime.size)
        val wordBeginTime = callOriginalLyricData<Array<LongArray>>(lyricData, "getWordBeginTime")
        val wordDelayTime = callOriginalLyricData<Array<LongArray>>(lyricData, "getWordDelayTime")
        val translateWords = callOriginalLyricData<Array<Array<String>>>(lyricData, "getTranslateWords")
        val transliterationWords =
            callOriginalLyricData<Array<Array<String>>>(lyricData, "getTransliterationWords")

        val lines = words.mapIndexedNotNull { index, rowWords ->
            val text = rowWords.joinToString(separator = "").trim()
            if (text.isBlank()) return@mapIndexedNotNull null

            val begin = rowBeginTime.getOrNull(index) ?: 0L
            val duration = rowDelayTime.getOrNull(index)
                ?.takeIf { it > 0L }
                ?: ((rowBeginTime.getOrNull(index + 1) ?: begin) - begin).takeIf { it > 0L }
                ?: 0L
            val end = begin + duration
            val lyricWords = buildOriginalLyricWords(
                rowWords = rowWords,
                lineBegin = begin,
                lineEnd = end,
                wordBegins = wordBeginTime?.getOrNull(index),
                wordDurations = wordDelayTime?.getOrNull(index)
            )

            RichLyricLine(
                begin = begin,
                end = end,
                duration = duration,
                text = text,
                words = lyricWords,
                roma = originalLyricRowText(transliterationWords, index),
                translation = originalLyricRowText(translateWords, index)
            )
        }.sortedBy { it.begin }

        diagnoseDebug("KG_ORIG parsed official LyricData lines=${lines.size}")
        return lines
    }

    private fun buildOriginalLyricWords(
        rowWords: Array<String>,
        lineBegin: Long,
        lineEnd: Long,
        wordBegins: LongArray?,
        wordDurations: LongArray?
    ): List<LyricWord> {
        if (rowWords.isEmpty()) return emptyList()
        return rowWords.mapIndexedNotNull { index, rawWord ->
            val text = rawWord.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawBegin = wordBegins?.getOrNull(index) ?: 0L
            val begin = when {
                rawBegin in lineBegin..lineEnd -> rawBegin
                else -> lineBegin + rawBegin
            }.coerceAtLeast(lineBegin)
            val duration = wordDurations?.getOrNull(index)
                ?.takeIf { it > 0L }
                ?: ((wordBegins?.getOrNull(index + 1)?.let { next ->
                    val normalizedNext = if (next in lineBegin..lineEnd) next else lineBegin + next
                    normalizedNext - begin
                })?.takeIf { it > 0L })
                ?: 0L
            val end = if (duration > 0L) {
                (begin + duration).coerceAtMost(lineEnd.takeIf { it > lineBegin } ?: begin + duration)
            } else {
                begin
            }
            LyricWord(
                begin = begin,
                end = end,
                duration = duration,
                text = text
            )
        }
    }

    private fun originalLyricRowText(rows: Array<Array<String>>?, index: Int): String? {
        return rows?.getOrNull(index)
            ?.joinToString(separator = "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> callOriginalLyricData(lyricData: Any, methodName: String): T? {
        return runCatching {
            lyricData.javaClass.getMethod(methodName).invoke(lyricData) as? T
        }.getOrNull()
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve().apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState
                        provider?.player?.setPlaybackState(state)
                        if (shouldThrottleBridgePlaybackState()) {
                            throttledSendPlaybackState(state)
                        } else {
                            val snapshot = currentTrackSnapshot()
                            SaltLyricBridge.sendPlaybackState(
                                appContext,
                                state,
                                snapshot.metadata,
                                snapshot.generation
                            )
                        }
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    before {
                        if (useOriginalApkLyricPipeline()) {
                            return@before
                        }

                        if (!shouldStabilizeNoisyMetadataIdentity()) return@before
                        if (KuGouLyricInfoPublisher.isSelfPublishing()) return@before
                        val metadata = args[0] as? MediaMetadata ?: return@before

                        val incoming = metadataDataFrom(metadata)
                        if (incoming != null) {
                            val snapshot = currentTrackSnapshot()
                            val target = sanitizeTargetForNoisyMetadata(incoming, snapshot)
                            if (target != null) {
                                val isSameTitle = normalizeLocalLyricFileText(target.title) == normalizeLocalLyricFileText(incoming.title)
                                val isSameArtist = normalizeLocalLyricFileText(target.artist) == normalizeLocalLyricFileText(incoming.artist)
                                if (!isSameTitle || !isSameArtist) {
                                    if (!shouldUseCarLyricFallback()) {
                                        diagnose(
                                            "KG_DIAG block car lyric setMetadata: ${incoming.title}"
                                        )
                                        result = null
                                        return@before
                                    }
                                }
                            }
                        }

                        rememberStableArtworkMetadata(metadata)
                        var patched = sanitizeNoisyOfficialMetadata(metadata)
                        if (!patched.hasKuGouArtworkBitmap()) {
                            val incomingData = metadataDataFrom(patched)
                            if (incomingData != null) {
                                val snapshot = currentTrackSnapshot()
                                val current = snapshot.metadata
                                val pending = synchronized(stateLock) { pendingMetadataIdentity }
                                val matches = when {
                                    pending != null && looksLikeMetadataForTrack(incomingData, pending) -> true
                                    current != null && looksLikeMetadataForTrack(incomingData, current) -> true
                                    else -> false
                                }
                                if (matches) {
                                    val builder = MediaMetadata.Builder(patched)
                                    copyCachedArtwork(builder, incomingData)
                                    patched = builder.build()
                                }
                            }
                        }
                        if (patched !== metadata) {
                            args[0] = patched
                        }
                    }
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
                        if (useOriginalApkLyricPipeline()) {
                            val incoming = metadataDataFrom(metadata) ?: return@after
                            if (shouldIgnoreOriginalCarLyricMetadata(incoming)) {
                                diagnoseDebug(
                                    "KG_ORIG ignore car lyric metadata: ${incoming.title.take(80)}"
                                )
                            } else {
                                handleOriginalMetadataChange(incoming)
                            }
                            return@after
                        }
                        val session = instance as? MediaSession
                        if (session != null && shouldPublishMediaSessionLyricInfo()) {
                            KuGouLyricInfoPublisher.onMetadata(session, metadata)
                        }
                        if (KuGouLyricInfoPublisher.isSelfPublishing()) return@after
                        handleMetadataChange(metadata)
                    }
                }
            }
    }

    private fun throttledSendPlaybackState(state: PlaybackState?) {
        if (state == null) return
        val stateCode = state.state
        val now = SystemClock.elapsedRealtime()
        val isPlaying = stateCode == PlaybackState.STATE_PLAYING ||
            stateCode == PlaybackState.STATE_FAST_FORWARDING ||
            stateCode == PlaybackState.STATE_REWINDING ||
            stateCode == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            stateCode == PlaybackState.STATE_SKIPPING_TO_PREVIOUS

        synchronized(stateLock) {
            // Always forward playing states so Bridge can start playback sync.
            if (!isPlaying) {
                // During the track-change suppression window, drop transient
                // paused/stopped states to avoid triggering "external playback reset"
                // soft-switches that cause lyric flicker.
                if (now < trackChangeSuppressUntilElapsed) {
                    return
                }
                // Throttle consecutive identical non-playing state codes.
                if (stateCode == lastForwardedPlaybackStateCode &&
                    now - lastForwardedPlaybackStateElapsed < PLAYBACK_STATE_THROTTLE_MS
                ) {
                    return
                }
            }
            lastForwardedPlaybackStateCode = stateCode
            lastForwardedPlaybackStateElapsed = now
        }
        val snapshot = currentTrackSnapshot()
        SaltLyricBridge.sendPlaybackState(
            appContext,
            state,
            snapshot.metadata,
            snapshot.generation
        )
    }

    private fun handleMetadataChange(metadata: MediaMetadata) {
        val meta = metadataDataFrom(metadata) ?: return
        if (handleCarLyricMetadata(meta)) return
        if (shouldStabilizeNoisyMetadataIdentity()) {
            handleNoisyMetadataIdentity(meta)
            return
        }

        acceptMetadataIdentity(meta)
    }

    private fun handleOriginalMetadataChange(meta: MetadataData) {
        if (isSuspiciousMetadataIdentity(meta, currentTrackSnapshot().metadata)) {
            diagnoseDebug(
                "KG_ORIG ignore suspicious metadata id=${meta.identityId.take(48)} " +
                    "title=${meta.title.take(64)}"
            )
            return
        }
        if (shouldStabilizeNoisyMetadataIdentity()) {
            handleNoisyMetadataIdentity(meta)
            return
        }
        acceptMetadataIdentity(meta)
    }

    private fun shouldIgnoreOriginalCarLyricMetadata(meta: MetadataData): Boolean {
        val snapshot = currentTrackSnapshot()
        val current = snapshot.metadata

        if (looksLikeCreditMetadataLine(meta.title)) return true
        if (current == null || snapshot.generation <= 0L) return false

        val currentTitle = normalizeLocalLyricFileText(current.title)
        val incomingTitle = normalizeLocalLyricFileText(meta.title)
        if (incomingTitle.isBlank() || currentTitle == incomingTitle) return false

        val stableMedia = sameStableMedia(current, meta)
        val sameTrackChurn = looksLikeSameTrackMetadataChurn(current, meta, currentTitle, incomingTitle)
        val lyricLineForCurrentTrack =
            hasLyricReadyForGeneration(snapshot.generation) && looksLikeMetadataForTrack(meta, current)

        return stableMedia || sameTrackChurn || lyricLineForCurrentTrack
    }

    private fun metadataDataFrom(metadata: MediaMetadata): MetadataData? {
        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata.description.title?.toString()
        ) ?: return null
        val artist = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            metadata.description.subtitle?.toString()
        ).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.description.mediaId
        ).orEmpty()
        val mediaUri = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            metadata.description.mediaUri?.toString()
        ).orEmpty()

        return MetadataData(title, artist, album, duration, mediaId, mediaUri)
    }

    private fun handleNoisyMetadataIdentity(meta: MetadataData) {
        val snapshot = currentTrackSnapshot()
        if (snapshot.songId == meta.identityId) {
            acceptMetadataIdentity(meta)
            return
        }

        if (isSuspiciousMetadataIdentity(meta, snapshot.metadata)) {
            diagnoseDebug(
                "KG_DIAG ignore suspicious metadata identity id=${meta.identityId.take(48)} " +
                    "title=${meta.title.take(64)} key=${meta.trackKey.take(80)}"
            )
            return
        }

        scheduleStableMetadataIdentity(meta)
    }

    private fun sanitizeNoisyOfficialMetadata(metadata: MediaMetadata): MediaMetadata {
        val incoming = metadataDataFrom(metadata) ?: return metadata
        val snapshot = currentTrackSnapshot()
        val target = sanitizeTargetForNoisyMetadata(incoming, snapshot) ?: return metadata

        val isSameTitle = normalizeLocalLyricFileText(target.title) == normalizeLocalLyricFileText(incoming.title)
        val isSameArtist = normalizeLocalLyricFileText(target.artist) == normalizeLocalLyricFileText(incoming.artist)
        if (isSameTitle && isSameArtist) return metadata

        diagnoseDebug(
            "KG_DIAG sanitize noisy official metadata title=${incoming.title.take(64)} " +
                "target=${target.title.take(64)}"
        )
        return buildMetadataForStableTrack(metadata, target)
    }

    private fun sanitizeTargetForNoisyMetadata(
        incoming: MetadataData,
        snapshot: TrackSnapshot
    ): MetadataData? {
        val pending = synchronized(stateLock) { pendingMetadataIdentity }
        if (pending != null && looksLikeMetadataForTrack(incoming, pending)) {
            return pending
        }

        val current = snapshot.metadata ?: return null
        if (snapshot.generation <= 0L || !hasLyricReadyForGeneration(snapshot.generation)) {
            return null
        }
        if (looksLikeMetadataForTrack(incoming, current)) {
            return current
        }
        return null
    }

    private fun buildMetadataForStableTrack(source: MediaMetadata, meta: MetadataData): MediaMetadata {
        val builder = MediaMetadata.Builder(source)
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, meta.artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, meta.artist)
            .putString(
                MediaMetadata.METADATA_KEY_ALBUM,
                meta.album.ifBlank { source.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() }
            )
            .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.duration)
        copyCachedArtwork(builder, meta)
        return builder.build()
    }

    private fun rememberStableArtworkMetadata(metadata: MediaMetadata) {
        if (!metadata.hasKuGouArtwork()) return
        val incoming = metadataDataFrom(metadata) ?: return
        val snapshot = currentTrackSnapshot()
        val current = snapshot.metadata
        val pending = synchronized(stateLock) { pendingMetadataIdentity }
        val artworkIdentityId = when {
            pending != null && looksLikeMetadataForTrack(incoming, pending) -> pending.identityId
            current != null && looksLikeMetadataForTrack(incoming, current) -> current.identityId
            else -> incoming.identityId
        }
        val hasNewBitmap = metadata.hasKuGouArtworkBitmap()
        synchronized(stateLock) {
            val existingArtwork = artworkMetadataCache[artworkIdentityId]
            val hasExistingBitmap = existingArtwork?.hasKuGouArtworkBitmap() ?: false
            if (hasNewBitmap || existingArtwork == null || !hasExistingBitmap) {
                artworkMetadataCache[artworkIdentityId] = metadata
                lastArtworkIdentityId = artworkIdentityId
                lastArtworkMetadata = metadata
                diagnoseDebug(
                    "KG_DIAG cache artwork metadata id=${artworkIdentityId.take(48)} " +
                        "source=${incoming.identityId.take(48)} (hasBitmap=$hasNewBitmap)"
                )
            } else {
                diagnoseDebug(
                    "KG_DIAG skip cache update (preserved old bitmap) id=${artworkIdentityId.take(48)}"
                )
            }
        }
    }

    private fun copyCachedArtwork(builder: MediaMetadata.Builder, meta: MetadataData) {
        val artwork = synchronized(stateLock) {
            artworkMetadataCache[meta.identityId] ?: lastArtworkMetadata?.takeIf {
                lastArtworkIdentityId == meta.identityId ||
                    metadataDataFrom(it)?.let { cachedMeta ->
                        looksLikeMetadataForTrack(cachedMeta, meta)
                    } == true
            }
        } ?: return
        diagnoseDebug(
            "KG_DIAG copy artwork metadata target=${meta.identityId.take(48)} " +
                "source=$lastArtworkIdentityId"
        )
        builder.copyKuGouArtworkFrom(artwork)
    }

    private fun acceptMetadataIdentity(meta: MetadataData, resolveLyrics: Boolean = true): Long {
        val songId = meta.identityId

        val now = SystemClock.elapsedRealtime()
        val generation: Long
        val trackChanged: Boolean
        var effectiveMeta = meta
        var lyricAlreadyReadyForGeneration = false
        var retainedStableRefresh = false
        synchronized(stateLock) {
            trackChanged = songId != currentSongId
            if (trackChanged) {
                currentSongId = songId
                currentTrackGeneration = nextTrackGenerationLocked(now)
                currentTrackChangedAtElapsed = now
                lastEmittedSong = null
                lastEmittedSongGeneration = 0L
                lastEmittedSongSignature = ""
                trackChangeSuppressUntilElapsed =
                    now + PLAYBACK_STATE_TRACK_CHANGE_SUPPRESS_MS
                lastArtworkMetadata = null
                lastArtworkIdentityId = ""
                currentMetadata = meta
            } else {
                val currentGeneration = currentTrackGeneration
                lyricAlreadyReadyForGeneration =
                    currentGeneration > 0L && lastLyricReadyGeneration >= currentGeneration
                val current = currentMetadata
                if (shouldRetainStableMetadataRefresh(
                        current,
                        meta,
                        lyricAlreadyReadyForGeneration
                    )
                ) {
                    effectiveMeta = current!!
                    retainedStableRefresh = true
                } else {
                    currentMetadata = meta
                }
            }
            generation = currentTrackGeneration
        }

        if (trackChanged) {
            diagnose(
                "KG_DIAG metadata changed gen=$generation id=${songId.take(48)} " +
                    "key=${effectiveMeta.trackKey.take(80)} duration=${effectiveMeta.duration}"
            )
            if (shouldPublishMediaSessionLyricInfo()) {
                KuGouLyricInfoPublisher.onTrackChanged(effectiveMeta, generation)
            }
            scheduleTrackChanged(effectiveMeta, generation)
        } else if (retainedStableRefresh) {
            diagnoseDebug(
                "KG_DIAG keep stable metadata refresh gen=$generation " +
                    "current=${effectiveMeta.trackKey.take(80)} incoming=${meta.trackKey.take(80)}"
            )
        } else {
            diagnoseDebug(
                "KG_DIAG metadata refreshed gen=$generation id=${songId.take(48)} " +
                    "key=${effectiveMeta.trackKey.take(80)}"
            )
        }

        if (!resolveLyrics) {
            return generation
        }
        if (!trackChanged && lyricAlreadyReadyForGeneration) {
            return generation
        }
        if (trySendCachedLyrics(effectiveMeta, generation)) {
            return generation
        }
        if (tryBindPendingLyricsToCurrent("metadata")) {
            return generation
        }
        scheduleLocalLyricFileProbe(effectiveMeta, generation)
        return generation
    }

    private fun shouldRetainStableMetadataRefresh(
        current: MetadataData?,
        incoming: MetadataData,
        lyricAlreadyReadyForGeneration: Boolean
    ): Boolean {
        if (!shouldUseCarLyricFallback() ||
            !lyricAlreadyReadyForGeneration ||
            current == null
        ) {
            return false
        }

        val currentTitle = normalizeLocalLyricFileText(current.title)
        val incomingTitle = normalizeLocalLyricFileText(incoming.title)
        val currentArtist = normalizeLocalLyricFileText(current.artist)
        val incomingArtist = normalizeLocalLyricFileText(incoming.artist)
        if (currentTitle == incomingTitle && currentArtist == incomingArtist) {
            return false
        }

        return sameStableMedia(current, incoming) ||
            looksLikeSameTrackMetadataChurn(current, incoming, currentTitle, incomingTitle) ||
            looksLikeMetadataForTrack(incoming, current)
    }

    private fun scheduleStableMetadataIdentity(meta: MetadataData) {
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            val pending = pendingMetadataIdentity
            if (pending?.identityId == meta.identityId) {
                pendingMetadataIdentity = meta
                diagnoseDebug(
                    "KG_DIAG refresh pending metadata identity id=${meta.identityId.take(48)} " +
                        "age=${now - pendingMetadataIdentityStartedAtElapsed}ms"
                )
                return
            }
            pendingMetadataIdentityJob?.cancel()
            pendingMetadataIdentity = meta
            pendingMetadataIdentityStartedAtElapsed = now
            pendingMetadataIdentityJob = scope.launch {
                delay(NOISY_METADATA_IDENTITY_STABILIZE_MS)
                promotePendingMetadataIdentity(meta.identityId)
            }
        }
        diagnoseDebug(
            "KG_DIAG pending metadata identity id=${meta.identityId.take(48)} " +
                "delay=${NOISY_METADATA_IDENTITY_STABILIZE_MS}ms key=${meta.trackKey.take(80)}"
        )
    }

    private fun promotePendingMetadataIdentity(expectedIdentityId: String) {
        val meta = synchronized(stateLock) {
            val pending = pendingMetadataIdentity ?: return
            if (pending.identityId != expectedIdentityId || currentSongId == pending.identityId) {
                return
            }
            pendingMetadataIdentity = null
            pendingMetadataIdentityJob = null
            pendingMetadataIdentityStartedAtElapsed = 0L
            pending
        }
        diagnoseDebug(
            "KG_DIAG promote pending metadata identity id=${meta.identityId.take(48)} " +
                "key=${meta.trackKey.take(80)}"
        )
        acceptMetadataIdentity(meta)
    }

    private fun handleCarLyricMetadata(meta: MetadataData): Boolean {
        if (!shouldUseCarLyricFallback()) {
            val snapshot = currentTrackSnapshot()
            val current = snapshot.metadata ?: return false
            if (snapshot.generation <= 0L) {
                return false
            }
            val currentTitle = normalizeLocalLyricFileText(current.title)
            val incomingTitle = normalizeLocalLyricFileText(meta.title)
            if (incomingTitle.isBlank() || currentTitle == incomingTitle) {
                return false
            }
            val stableMedia = sameStableMedia(current, meta)
            val sameTrackChurn = looksLikeSameTrackMetadataChurn(current, meta, currentTitle, incomingTitle)
            return stableMedia || sameTrackChurn
        }

        val snapshot = currentTrackSnapshot()
        val current = snapshot.metadata ?: return false
        if (snapshot.generation <= 0L) {
            return false
        }
        val currentTitle = normalizeLocalLyricFileText(current.title)
        val incomingTitle = normalizeLocalLyricFileText(meta.title)
        if (incomingTitle.isBlank() || currentTitle == incomingTitle) {
            return false
        }
        val stableMedia = sameStableMedia(current, meta)
        val sameTrackChurn = looksLikeSameTrackMetadataChurn(current, meta, currentTitle, incomingTitle)
        if (!stableMedia && !sameTrackChurn) {
            return false
        }

        if (hasLyricReadyForGeneration(snapshot.generation)) {
            diagnoseDebug(
                "KG_DIAG ignore car lyric metadata after lyricReady gen=${snapshot.generation} " +
                    "stable=$stableMedia churn=$sameTrackChurn text=${meta.title.take(64)}"
            )
            return true
        }
        provider?.player?.sendText(meta.title)
        diagnoseDebug(
            "KG_DIAG car lyric fallback line gen=${snapshot.generation} " +
                "stable=$stableMedia churn=$sameTrackChurn text=${meta.title.take(64)}"
        )
        return true
    }

    private fun looksLikeSameTrackMetadataChurn(
        current: MetadataData,
        incoming: MetadataData,
        currentTitle: String,
        incomingTitle: String
    ): Boolean {
        if (current.duration <= 0L || current.duration != incoming.duration) return false
        if (currentTitle.isBlank() || incomingTitle.isBlank()) return false
        val currentArtist = normalizeLocalLyricFileText(current.artist)
        val incomingArtist = normalizeLocalLyricFileText(incoming.artist)
        val titleDecorated = incomingTitle.contains(currentTitle) &&
            (currentArtist.isBlank() ||
                incomingTitle.contains(currentArtist) ||
                incomingArtist.contains(currentArtist))
        val artistMergedWithTitle = currentArtist.isNotBlank() &&
            incomingArtist.contains(currentArtist) &&
            incomingArtist.contains(currentTitle)
        val creditLineForCurrentTrack = looksLikeCreditMetadataLine(incoming.title) &&
            (incomingTitle.contains(currentTitle) || incomingArtist.contains(currentTitle))
        return titleDecorated || artistMergedWithTitle || creditLineForCurrentTrack
    }

    private fun isSuspiciousMetadataIdentity(meta: MetadataData, current: MetadataData?): Boolean {
        if (looksLikeCreditMetadataLine(meta.title)) return true
        if (looksLikeCarLyricDisplayMetadata(meta, current)) return true
        return false
    }

    private fun looksLikeMetadataForTrack(incoming: MetadataData, track: MetadataData): Boolean {
        if (incoming.identityId == track.identityId) return true
        if (incoming.duration > 0L && track.duration > 0L && incoming.duration != track.duration) {
            return false
        }

        val incomingTitle = normalizeLocalLyricFileText(incoming.title)
        val incomingArtist = normalizeLocalLyricFileText(incoming.artist)
        val trackTitle = normalizeLocalLyricFileText(track.title)
        val trackArtist = normalizeLocalLyricFileText(track.artist)
        if (trackTitle.isNotBlank() &&
            (incomingTitle.contains(trackTitle) || incomingArtist.contains(trackTitle))
        ) {
            return true
        }
        return trackArtist.isNotBlank() &&
            (incomingTitle.contains(trackArtist) || incomingArtist.contains(trackArtist))
    }

    private fun looksLikeCarLyricDisplayMetadata(meta: MetadataData, current: MetadataData?): Boolean {
        val rawTitle = meta.title.trim()
        if (rawTitle.length < 12) return false
        val hasDisplaySeparator = rawTitle.contains(" - ") ||
            rawTitle.contains(" – ") ||
            rawTitle.contains(" — ") ||
            rawTitle.contains("｜") ||
            rawTitle.contains(" / ")
        if (!hasDisplaySeparator) return false
        if (meta.artist.isNotBlank()) return true

        val title = normalizeLocalLyricFileText(rawTitle)
        val artist = normalizeLocalLyricFileText(meta.artist)
        if (artist.length >= 2 && title.contains(artist)) return true

        val currentTitle = normalizeLocalLyricFileText(current?.title)
        val currentArtist = normalizeLocalLyricFileText(current?.artist)
        return currentTitle.isNotBlank() &&
            (title.contains(currentTitle) ||
                artist.contains(currentTitle) ||
                currentArtist.isNotBlank() && title.contains(currentArtist))
    }

    private fun looksLikeCreditMetadataLine(value: String): Boolean {
        return value.isNotBlank() && CAR_LYRIC_CREDIT_TOKEN_REGEX.containsMatchIn(value)
    }

    private fun sameStableMedia(current: MetadataData, incoming: MetadataData): Boolean {
        if (current.mediaId.isNotBlank() && current.mediaId == incoming.mediaId) return true
        if (current.mediaUri.isNotBlank() && current.mediaUri == incoming.mediaUri) return true
        return current.duration > 0L &&
            current.duration == incoming.duration &&
            normalizeLocalLyricFileText(current.artist) == normalizeLocalLyricFileText(incoming.artist) &&
            normalizeLocalLyricFileText(current.album) == normalizeLocalLyricFileText(incoming.album)
    }

    private fun nextTrackGenerationLocked(nowElapsedMs: Long): Long {
        return maxOf(currentTrackGeneration + 1L, nowElapsedMs)
    }

    private fun currentTrackSnapshot(): TrackSnapshot {
        return synchronized(stateLock) {
            TrackSnapshot(
                songId = currentSongId,
                generation = currentTrackGeneration,
                metadata = currentMetadata,
                changedAtElapsed = currentTrackChangedAtElapsed
            )
        }
    }

    private fun scheduleTrackChanged(meta: MetadataData, generation: Long) {
        synchronized(stateLock) {
            pendingTrackChangedJob?.cancel()
            pendingTrackChangedJob = scope.launch {
                delay(TRACK_CHANGED_DEBOUNCE_MS)
                sendDelayedTrackChanged(meta, generation)
            }
        }
    }

    private fun sendDelayedTrackChanged(meta: MetadataData, generation: Long) {
        val shouldSend = synchronized(stateLock) {
            currentTrackGeneration == generation && lastLyricReadyGeneration < generation
        }
        if (!shouldSend) {
            diagnoseDebug("KG_DIAG suppress trackChanged gen=$generation; lyricReady already won")
            return
        }
        diagnose(
            "KG_DIAG send trackChanged gen=$generation id=${meta.identityId.take(48)} " +
                "key=${meta.trackKey.take(80)}"
        )
        SaltLyricBridge.sendTrackChanged(appContext, meta, generation)
    }

    private fun trySendCachedLyrics(meta: MetadataData, generation: Long): Boolean {
        val cachedLyrics = LyricsCache.get(meta.identityKeys) ?: return false
        diagnoseDebug(
            "KG_DIAG cache hit gen=$generation id=${meta.identityId.take(48)} " +
                "key=${meta.trackKey.take(80)} lines=${cachedLyrics.size}"
        )
        return sendLyricsForMeta(cachedLyrics, meta, generation, "cache")
    }

    private fun scheduleLocalLyricFileProbe(meta: MetadataData, generation: Long) {
        synchronized(stateLock) {
            if (pendingLocalLyricProbeGeneration == generation) return

            pendingLocalLyricProbeJob?.cancel()
            pendingLocalLyricProbeGeneration = generation
            pendingLocalLyricProbeJob = scope.launch {
                runLocalLyricResolutionSchedule(meta, generation)
            }
        }
    }

    private suspend fun runLocalLyricResolutionSchedule(meta: MetadataData, generation: Long) {
        var elapsedDelayMs = 0L
        for (delayMs in LOCAL_LYRIC_FILE_FAST_PROBE_DELAYS_MS) {
            if (delayMs > elapsedDelayMs) {
                delay(delayMs - elapsedDelayMs)
                elapsedDelayMs = delayMs
            }
            if (resolveLyricsFallback(meta, generation, "local-file-${delayMs}ms")) return
            if (!isCurrentTrackGeneration(meta, generation) || hasLyricReadyForGeneration(generation)) return
        }

        repeat(LOCAL_LYRIC_FILE_FALLBACK_RETRY_COUNT) { index ->
            val attempt = index + 1
            delay(LOCAL_LYRIC_FILE_FALLBACK_RETRY_INTERVAL_MS)
            elapsedDelayMs += LOCAL_LYRIC_FILE_FALLBACK_RETRY_INTERVAL_MS
            if (resolveLyricsFallback(
                    meta,
                    generation,
                    "local-file-fallback-$attempt-${elapsedDelayMs}ms"
                )
            ) {
                return
            }
            if (!isCurrentTrackGeneration(meta, generation) || hasLyricReadyForGeneration(generation)) {
                return
            }
        }
    }

    private fun resolveLyricsFallback(
        meta: MetadataData,
        generation: Long,
        reason: String
    ): Boolean {
        if (!isCurrentTrackGeneration(meta, generation) || hasLyricReadyForGeneration(generation)) {
            return false
        }
        if (trySendCachedLyrics(meta, generation)) {
            return true
        }
        if (tryBindPendingLyricsToCurrent(reason)) {
            return true
        }
        return probeLocalLyricFile(meta, generation, reason)
    }

    private fun probeLocalLyricFile(
        meta: MetadataData,
        generation: Long,
        reason: String
    ): Boolean {
        if (!isCurrentTrackGeneration(meta, generation) || hasLyricReadyForGeneration(generation)) {
            return false
        }
        val file = findBestLocalLyricFile(meta) ?: return false
        val startedAtElapsed = SystemClock.elapsedRealtime()
        val lyrics = runCatching { parseLyricFile(file) }
            .onFailure {
                YLog.error(tag = TAG, msg = "Local lyric probe failed: ${it.message}")
            }
            .getOrDefault(emptyList())
        if (lyrics.isEmpty()) {
            diagnoseDebug("KG_DIAG local lyric probe empty gen=$generation path=${file.path.takeLast(96)}")
            return false
        }
        diagnose(
            "KG_DIAG local lyric probe hit gen=$generation reason=$reason " +
                "lines=${lyrics.size} path=${file.path.takeLast(96)}"
        )
        return bindLyricCandidateToCurrent(
            LyricCandidate(
                lyrics = lyrics,
                capturedSongId = meta.identityId,
                capturedGeneration = generation,
                path = file.path,
                startedAtElapsed = startedAtElapsed,
                completedAtElapsed = SystemClock.elapsedRealtime()
            ),
            reason
        )
    }

    private fun findBestLocalLyricFile(meta: MetadataData): File? {
        val query = buildLocalLyricQuery(meta) ?: return null
        var bestFile: File? = null
        var bestScore = 0
        var bestModified = 0L
        candidateLyricDirectories().forEach { directory ->
            directory.root.listFiles()?.forEach { file ->
                if (!file.isFile || file.length() <= 0L || !isSupportedLyricFile(file)) return@forEach
                val score = localLyricFileScore(file, query, directory.label)
                if (score <= 0) return@forEach
                val modified = file.lastModified()
                if (score > bestScore || score == bestScore && modified > bestModified) {
                    bestFile = file
                    bestScore = score
                    bestModified = modified
                }
            }
        }
        return bestFile
    }

    private fun candidateLyricDirectories(): List<LocalLyricDirectory> {
        val ctx = appContext ?: return emptyList()
        return listOf(
            LocalLyricDirectory(File(ctx.filesDir, "kugou/lyrics"), "files/kugou/lyrics"),
            LocalLyricDirectory(File(ctx.filesDir, "lyrics"), "files/lyrics"),
            LocalLyricDirectory(File(ctx.cacheDir, "kugou/lyrics"), "cache/kugou/lyrics"),
            LocalLyricDirectory(File(ctx.cacheDir, "lyrics"), "cache/lyrics")
        ).filter { it.root.isDirectory }
    }

    private fun isSupportedLyricFile(file: File): Boolean {
        return when (file.extension.lowercase()) {
            "krc", "lyc", "lrc", "txt" -> true
            else -> false
        }
    }

    private fun buildLocalLyricQuery(meta: MetadataData): LocalLyricQuery? {
        val normalizedTitle = normalizeLocalLyricFileText(meta.title)
        if (normalizedTitle.isBlank()) return null
        val normalizedArtist = normalizeLocalLyricFileText(meta.artist)
        if (normalizedArtist.isBlank() && normalizedTitle.length < 4) return null
        return LocalLyricQuery(
            normalizedTitle = normalizedTitle,
            normalizedArtist = normalizedArtist,
            artistTitle = normalizedArtist + normalizedTitle,
            titleArtist = normalizedTitle + normalizedArtist
        )
    }

    private fun localLyricFileScore(file: File, query: LocalLyricQuery, directoryLabel: String): Int {
        val stem = stripKuGouHashSuffix(file.nameWithoutExtension)
        val normalizedStem = normalizeLocalLyricFileText(stem)
        val titleMatched = normalizedStem.contains(query.normalizedTitle)
        val artistMatched = query.normalizedArtist.isNotBlank() &&
            normalizedStem.contains(query.normalizedArtist)
        if (!titleMatched) return 0
        if (query.normalizedArtist.isNotBlank() && !artistMatched) {
            if (query.normalizedTitle.length < 4) return 0
            return if (directoryLabel == "files/kugou/lyrics" || directoryLabel == "files/lyrics") {
                58
            } else {
                52
            }
        }

        var score = 70
        if (artistMatched) score += 25
        if (directoryLabel == "files/kugou/lyrics" || directoryLabel == "files/lyrics") {
            score += 3
        }
        if (query.normalizedArtist.isNotBlank()) {
            if (normalizedStem.contains(query.artistTitle) || normalizedStem.contains(query.titleArtist)) {
                score += 10
            }
        }
        return score
    }

    private fun stripKuGouHashSuffix(value: String): String {
        return value.replace(KUGOU_HASH_SUFFIX_REGEX, "")
    }

    private fun normalizeLocalLyricFileText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val builder = StringBuilder(value.length)
        value.trim().lowercase().forEach { ch ->
            if (ch.isLetterOrDigit() || ch.code > 0x7F) {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun isCurrentTrackGeneration(meta: MetadataData, generation: Long): Boolean {
        return synchronized(stateLock) {
            currentTrackGeneration == generation && currentMetadata?.identityId == meta.identityId
        }
    }

    private fun hasLyricReadyForGeneration(generation: Long): Boolean {
        return synchronized(stateLock) {
            currentTrackGeneration == generation && lastLyricReadyGeneration >= generation
        }
    }

    private fun handleLyricCandidate(candidate: LyricCandidate, reason: String) {
        diagnoseDebug(
            "KG_DIAG lyric candidate $reason capturedGen=${candidate.capturedGeneration} " +
                "capturedId=${candidate.capturedSongId.orEmpty().take(48)} " +
                "lines=${candidate.lyrics.size} path=${candidate.path.takeLast(96)}"
        )
        if (tryPromotePendingMetadataForLyricCandidate(candidate, reason)) {
            return
        }
        if (shouldDeferLyricCandidateForNoisyMetadata(candidate)) {
            rememberPendingLyricCandidate(candidate)
            schedulePendingLyricCandidateBind("deferred-$reason")
            diagnoseDebug(
                "KG_DIAG defer lyric candidate for noisy metadata reason=$reason " +
                    "capturedGen=${candidate.capturedGeneration}"
            )
            return
        }
        if (bindLyricCandidateToCurrent(candidate, reason)) {
            return
        }
        rememberPendingLyricCandidate(candidate)
        diagnoseDebug(
            "KG_DIAG pending lyric candidate reason=$reason capturedGen=${candidate.capturedGeneration} " +
                "capturedId=${candidate.capturedSongId.orEmpty().take(48)}"
        )
    }

    private fun shouldDeferLyricCandidateForNoisyMetadata(candidate: LyricCandidate): Boolean {
        if (useOriginalApkLyricPipeline()) return false
        if (!shouldStabilizeNoisyMetadataIdentity()) return false
        val snapshot = currentTrackSnapshot()
        if (snapshot.generation <= 0L || candidate.capturedGeneration != snapshot.generation) {
            return false
        }
        if (!hasLyricReadyForGeneration(snapshot.generation)) {
            return false
        }
        val hasPendingIdentity = synchronized(stateLock) { pendingMetadataIdentity != null }
        return !hasPendingIdentity
    }

    private fun schedulePendingLyricCandidateBind(reason: String) {
        synchronized(stateLock) {
            pendingLyricCandidateBindJob?.cancel()
            pendingLyricCandidateBindJob = scope.launch {
                delay(NOISY_LYRIC_CANDIDATE_GRACE_MS)
                tryBindPendingLyricsAfterNoisyMetadataGrace(reason)
            }
        }
    }

    private fun tryBindPendingLyricsAfterNoisyMetadataGrace(reason: String): Boolean {
        if (tryPromotePendingMetadataForBestLyricCandidate(reason)) {
            return true
        }
        if (shouldStabilizeNoisyMetadataIdentity()) {
            val hasPendingIdentity = synchronized(stateLock) { pendingMetadataIdentity != null }
            if (hasPendingIdentity) {
                diagnoseDebug(
                    "KG_DIAG keep pending lyric candidate while metadata identity stabilizes " +
                        "reason=$reason"
                )
                return false
            }
        }
        return tryBindPendingLyricsToCurrent(reason)
    }

    private fun tryPromotePendingMetadataForBestLyricCandidate(reason: String): Boolean {
        if (!shouldStabilizeNoisyMetadataIdentity()) return false
        val now = SystemClock.elapsedRealtime()
        val pair = synchronized(stateLock) {
            val pending = pendingMetadataIdentity ?: return false
            val startedAt = pendingMetadataIdentityStartedAtElapsed
            if (currentSongId == pending.identityId || startedAt <= 0L) return false
            prunePendingLyricCandidatesLocked(now)
            val candidate = pendingLyricCandidates
                .asSequence()
                .filter { isCandidateNearMetadataChange(it, startedAt) }
                .maxWithOrNull(compareBy<LyricCandidate> { it.completedAtElapsed })
                ?: return false
            pendingLyricCandidates.remove(candidate)
            pendingMetadataIdentityJob?.cancel()
            pendingMetadataIdentityJob = null
            pendingMetadataIdentity = null
            pendingMetadataIdentityStartedAtElapsed = 0L
            pending to candidate
        }

        val (pending, candidate) = pair
        val generation = acceptMetadataIdentity(pending, resolveLyrics = false)
        diagnoseDebug(
            "KG_DIAG promote pending metadata identity from deferred candidate gen=$generation " +
                "reason=$reason id=${pending.identityId.take(48)}"
        )
        return sendLyricsForMeta(candidate.lyrics, pending, generation, "pending-identity-$reason")
    }

    private fun tryPromotePendingMetadataForLyricCandidate(
        candidate: LyricCandidate,
        reason: String
    ): Boolean {
        if (!shouldStabilizeNoisyMetadataIdentity()) return false
        val pending = synchronized(stateLock) {
            val meta = pendingMetadataIdentity ?: return false
            val startedAt = pendingMetadataIdentityStartedAtElapsed
            if (currentSongId == meta.identityId ||
                !isCandidateNearMetadataChange(candidate, startedAt)
            ) {
                return false
            }
            pendingMetadataIdentityJob?.cancel()
            pendingMetadataIdentityJob = null
            pendingMetadataIdentity = null
            pendingMetadataIdentityStartedAtElapsed = 0L
            meta
        }

        val generation = acceptMetadataIdentity(pending, resolveLyrics = false)
        diagnoseDebug(
            "KG_DIAG promote pending metadata identity from lyric candidate gen=$generation " +
                "reason=$reason id=${pending.identityId.take(48)}"
        )
        return sendLyricsForMeta(candidate.lyrics, pending, generation, "pending-identity-$reason")
    }

    private fun isCandidateNearMetadataChange(
        candidate: LyricCandidate,
        changedAtElapsed: Long
    ): Boolean {
        if (changedAtElapsed <= 0L) return false
        val startsBeforeWindowEnd =
            candidate.startedAtElapsed <= changedAtElapsed + LYRIC_CANDIDATE_BIND_WINDOW_MS
        val completesAfterWindowStart =
            candidate.completedAtElapsed >= changedAtElapsed - LYRIC_CANDIDATE_EARLY_WINDOW_MS
        return startsBeforeWindowEnd && completesAfterWindowStart
    }

    private fun rememberPendingLyricCandidate(candidate: LyricCandidate) {
        synchronized(stateLock) {
            prunePendingLyricCandidatesLocked(SystemClock.elapsedRealtime())
            pendingLyricCandidates.addLast(candidate)
            while (pendingLyricCandidates.size > MAX_PENDING_LYRIC_CANDIDATES) {
                pendingLyricCandidates.removeFirst()
            }
        }
    }

    private fun tryBindPendingLyricsToCurrent(reason: String): Boolean {
        if (tryPromotePendingMetadataForBestLyricCandidate(reason)) {
            return true
        }
        if (shouldStabilizeNoisyMetadataIdentity()) {
            val hasPendingIdentity = synchronized(stateLock) { pendingMetadataIdentity != null }
            if (hasPendingIdentity) {
                diagnoseDebug(
                    "KG_DIAG suppress pending lyric bind until metadata identity settles " +
                        "reason=$reason"
                )
                return false
            }
        }
        val snapshot = currentTrackSnapshot()
        val meta = snapshot.metadata ?: return false
        val now = SystemClock.elapsedRealtime()
        val candidate = synchronized(stateLock) {
            prunePendingLyricCandidatesLocked(now)
            pendingLyricCandidates
                .asSequence()
                .map { it to lyricCandidateScore(it, snapshot, meta) }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<LyricCandidate, Int>> { it.second }
                    .thenBy { it.first.completedAtElapsed })
                ?.first
                ?.also { pendingLyricCandidates.remove(it) }
        } ?: return false
        diagnoseDebug(
            "KG_DIAG bind pending candidate gen=${snapshot.generation} reason=$reason " +
                "score=${lyricCandidateScore(candidate, snapshot, meta)}"
        )
        return bindLyricCandidateToCurrent(candidate, "pending-$reason")
    }

    private fun bindLyricCandidateToCurrent(candidate: LyricCandidate, reason: String): Boolean {
        val snapshot = currentTrackSnapshot()
        val meta = snapshot.metadata ?: return false
        val score = lyricCandidateScore(candidate, snapshot, meta)
        if (score <= 0) {
            diagnoseDebug(
                "KG_DIAG reject lyric candidate reason=$reason capturedGen=${candidate.capturedGeneration} " +
                    "currentGen=${snapshot.generation} capturedId=${candidate.capturedSongId.orEmpty().take(48)} " +
                    "currentId=${meta.identityId.take(48)}"
            )
            return false
        }
        diagnoseDebug(
            "KG_DIAG bind lyric candidate reason=$reason score=$score gen=${snapshot.generation} " +
                "id=${meta.identityId.take(48)} key=${meta.trackKey.take(80)}"
        )
        return sendLyricsForMeta(candidate.lyrics, meta, snapshot.generation, reason)
    }

    private fun lyricCandidateScore(
        candidate: LyricCandidate,
        snapshot: TrackSnapshot,
        meta: MetadataData
    ): Int {
        val generation = snapshot.generation
        if (generation <= 0L) return 0

        val capturedId = candidate.capturedSongId.orEmpty()
        if (capturedId.isNotBlank() && meta.identityKeys.contains(capturedId)) {
            return 100
        }
        if (candidate.capturedGeneration == generation) {
            return 90
        }
        if (isCandidateNearCurrentTrack(candidate, snapshot)) {
            if (capturedId.isBlank()) {
                return 70
            }
            val allowEarlierGeneration = shouldStabilizeNoisyMetadataIdentity() ||
                useOriginalApkLyricPipeline()
            val generationMatches =
                if (allowEarlierGeneration) {
                    candidate.capturedGeneration < generation
                } else {
                    candidate.capturedGeneration + 1 == generation
                }
            if (generationMatches) {
                return 60
            }
        }
        return 0
    }

    private fun isCandidateNearCurrentTrack(
        candidate: LyricCandidate,
        snapshot: TrackSnapshot
    ): Boolean {
        return isCandidateNearMetadataChange(candidate, snapshot.changedAtElapsed)
    }

    private fun sendLyricsForMeta(
        lyrics: List<RichLyricLine>,
        meta: MetadataData,
        trackGeneration: Long,
        reason: String
    ): Boolean {
        val snapshot = currentTrackSnapshot()
        if (snapshot.generation != trackGeneration || snapshot.metadata?.identityId != meta.identityId) {
            diagnoseDebug(
                "KG_DIAG skip send stale lyric reason=$reason expectedGen=$trackGeneration " +
                    "currentGen=${snapshot.generation} expectedId=${meta.identityId.take(48)} " +
                    "currentId=${snapshot.metadata?.identityId.orEmpty().take(48)}"
            )
            return false
        }

        val cleanLyrics = sanitizeLyricsForMeta(lyrics, meta)
        if (cleanLyrics.isEmpty()) {
            diagnoseDebug("KG_DIAG skip empty lyric after sanitize gen=$trackGeneration reason=$reason")
            return false
        }

        LyricsCache.put(meta.identityKeys, cleanLyrics)
        val finalDuration = if (meta.duration <= 0) {
            cleanLyrics.lastOrNull()?.end ?: 0L
        } else {
            meta.duration
        }
        val song = Song(
            id = meta.identityId,
            name = meta.title,
            artist = meta.artist,
            duration = finalDuration,
            lyrics = cleanLyrics
        )

        return emitSong(song, meta, trackGeneration, reason)
    }

    private fun sanitizeLyricsForMeta(
        lyrics: List<RichLyricLine>,
        meta: MetadataData
    ): List<RichLyricLine> {
        if (lyrics.size <= 1) return lyrics
        var cleanLyrics = lyrics
        val first = cleanLyrics.first()
        if (isMetadataLeadLyricLine(first, meta)) {
            diagnoseDebug(
                "KG_DIAG drop metadata lead lyric title=${meta.title.take(48)} " +
                    "line=${first.text.orEmpty().take(80)}"
            )
            cleanLyrics = cleanLyrics.drop(1)
        }
        if (useOriginalApkLyricPipeline()) {
            cleanLyrics = dropLeadingCreditLyrics(cleanLyrics, meta)
        }
        return cleanLyrics
    }

    private fun dropLeadingCreditLyrics(
        lyrics: List<RichLyricLine>,
        meta: MetadataData
    ): List<RichLyricLine> {
        if (lyrics.size <= 1) return lyrics
        var dropCount = 0
        for (line in lyrics) {
            val text = line.text.orEmpty()
            if (dropCount >= lyrics.lastIndex || !looksLikeCreditMetadataLine(text)) {
                break
            }
            dropCount++
        }
        if (dropCount <= 0) return lyrics
        diagnoseDebug(
            "KG_ORIG drop leading credit lyric lines count=$dropCount title=${meta.title.take(48)} " +
                "first=${lyrics.firstOrNull()?.text.orEmpty().take(80)}"
        )
        return lyrics.drop(dropCount)
    }

    private fun isMetadataLeadLyricLine(line: RichLyricLine, meta: MetadataData): Boolean {
        if (line.begin > 1_000L) return false
        val rawText = line.text.orEmpty()
        val text = normalizeLocalLyricFileText(rawText)
        val title = normalizeLocalLyricFileText(meta.title)
        val artist = normalizeLocalLyricFileText(meta.artist)
        if (text.isBlank() || title.isBlank()) return false
        if (artist.isBlank()) {
            return isTitleMetadataLead(text, title, rawText)
        }
        if (text.contains(title) && text.contains(artist)) return true
        return isTitleMetadataLead(text, title, rawText)
    }

    private fun isTitleMetadataLead(text: String, title: String, rawText: String): Boolean {
        if (title.length < 2 || !text.contains(title) || text == title) return false
        val hasMetadataSeparator = rawText.any { it == '-' || it == '/' || it == '／' || it == '|' }
        return hasMetadataSeparator || text.startsWith(title) && text.length > title.length + 1
    }

    private fun emitSong(
        song: Song,
        meta: MetadataData,
        trackGeneration: Long,
        reason: String
    ): Boolean {
        markLyricReady(trackGeneration)
        val songSignature = buildSongSignature(song)
        if (lastEmittedSongGeneration == trackGeneration &&
            lastEmittedSongSignature == songSignature
        ) {
            diagnoseDebug("KG_DIAG skip duplicate lyricReady gen=$trackGeneration reason=$reason")
            return true
        }
        lastEmittedSong = song
        lastEmittedSongGeneration = trackGeneration
        lastEmittedSongSignature = songSignature
        provider?.player?.setSong(song)
        SaltLyricBridge.send(appContext, song, meta.mediaUri, trackGeneration)
        if (shouldPublishMediaSessionLyricInfo()) {
            KuGouLyricInfoPublisher.onLyricReady(appContext, song, meta, trackGeneration)
        }
        diagnose(
            "KG_DIAG send lyricReady gen=$trackGeneration reason=$reason " +
                "id=${song.id.orEmpty().take(48)} title=${song.name.orEmpty().take(64)} " +
                "lines=${song.lyrics?.size ?: 0}"
        )
        return true
    }

    private fun buildSongSignature(song: Song): String {
        val lyrics = song.lyrics.orEmpty()
        val first = lyrics.firstOrNull()
        val last = lyrics.lastOrNull()
        return listOf(
            song.id.orEmpty(),
            song.name.orEmpty(),
            song.artist.orEmpty(),
            song.duration.toString(),
            lyrics.size.toString(),
            first?.begin?.toString().orEmpty(),
            first?.end?.toString().orEmpty(),
            first?.text.orEmpty(),
            last?.begin?.toString().orEmpty(),
            last?.end?.toString().orEmpty(),
            last?.text.orEmpty()
        ).joinToString("|").hashCode().toString()
    }

    private fun markLyricReady(trackGeneration: Long) {
        synchronized(stateLock) {
            if (trackGeneration >= lastLyricReadyGeneration) {
                lastLyricReadyGeneration = trackGeneration
            }
            if (trackGeneration == currentTrackGeneration) {
                pendingTrackChangedJob?.cancel()
                pendingTrackChangedJob = null
                pendingLocalLyricProbeJob?.cancel()
                pendingLocalLyricProbeJob = null
                pendingLocalLyricProbeGeneration = 0L
            }
        }
    }

    private fun prunePendingLyricCandidatesLocked(now: Long) {
        while (pendingLyricCandidates.isNotEmpty()
            && now - pendingLyricCandidates.first.completedAtElapsed > LYRIC_CANDIDATE_MAX_AGE_MS
        ) {
            pendingLyricCandidates.removeFirst()
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun diagnose(message: String) {
        if (isDiagnosticLoggable(Log.DEBUG)) {
            YLog.debug(tag = TAG, msg = message)
        }
    }

    private fun diagnoseDebug(message: String) {
        if (isDiagnosticLoggable(Log.VERBOSE)) {
            YLog.debug(tag = TAG, msg = message)
        }
    }

    private fun isDiagnosticLoggable(priority: Int): Boolean {
        return KUGOU_DIAGNOSTICS_ENABLED || Log.isLoggable(TAG, priority)
    }
}
