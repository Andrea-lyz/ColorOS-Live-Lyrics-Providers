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

abstract class KuGouBase : YukiBaseHooker() {

    companion object {
        protected const val TAG = "KuGouProvider"
        private const val KUGOU_DIAGNOSTICS_ENABLED = false
        private const val TRACK_CHANGED_DEBOUNCE_MS = 1_200L
        private const val LYRIC_FILE_READY_RETRY_COUNT = 5
        private const val LYRIC_FILE_READY_RETRY_DELAY_MS = 80L
        private const val LYRIC_CANDIDATE_BIND_WINDOW_MS = 1_500L
        private const val LYRIC_CANDIDATE_EARLY_WINDOW_MS = 500L
        private const val LYRIC_CANDIDATE_MAX_AGE_MS = 10_000L
        private val LOCAL_LYRIC_FILE_FAST_PROBE_DELAYS_MS = longArrayOf(0L, 220L, 760L, 1_600L)
        private const val LOCAL_LYRIC_FILE_FALLBACK_RETRY_COUNT = 3
        private const val LOCAL_LYRIC_FILE_FALLBACK_RETRY_INTERVAL_MS = 2_000L
        private const val MAX_PENDING_LYRIC_CANDIDATES = 8
        private val KUGOU_HASH_SUFFIX_REGEX = Regex("[0-9a-fA-F]{16,}$")
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
    private var lastLyricReadyGeneration = 0L
    private var pendingTrackChangedJob: Job? = null
    private var pendingLocalLyricProbeJob: Job? = null
    private var pendingLocalLyricProbeGeneration = 0L
    private val pendingLyricCandidates = ArrayDeque<LyricCandidate>()
    private var isInitialized = false

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
                processLyricFileAsync(path, "before-load")
            }

            override fun afterHookedMethod(param: MethodHookParam?) {
                val path = lyricPathFromParam(param) ?: return
                processLyricFileAsync(path, "after-load")
            }
        })
    }

    private fun lyricPathFromParam(param: XC_MethodHook.MethodHookParam?): String? {
        val args = param?.args ?: return null
        return args.getOrNull(0) as? String
    }

    private fun processLyricFileAsync(path: String, reason: String) {
        val capturedSnapshot = currentTrackSnapshot()
        val startedAtElapsed = SystemClock.elapsedRealtime()
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
                        SaltLyricBridge.sendPlaybackState(appContext, state)
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
                        val session = instance as? MediaSession
                        if (session != null) {
                            KuGouLyricInfoPublisher.onMetadata(session, metadata)
                        }
                        if (KuGouLyricInfoPublisher.isSelfPublishing()) return@after
                        handleMetadataChange(metadata)
                    }
                }
            }
    }

    private fun handleMetadataChange(metadata: MediaMetadata) {
        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata.description.title?.toString()
        ) ?: return
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

        val meta = MetadataData(title, artist, album, duration, mediaId, mediaUri)
        if (handleCarLyricMetadata(meta)) return

        val songId = meta.identityId

        val now = SystemClock.elapsedRealtime()
        val generation: Long
        val trackChanged: Boolean
        synchronized(stateLock) {
            trackChanged = songId != currentSongId
            if (trackChanged) {
                currentSongId = songId
                currentTrackGeneration = nextTrackGenerationLocked(now)
                currentTrackChangedAtElapsed = now
                lastEmittedSong = null
                lastEmittedSongGeneration = 0L
            }
            currentMetadata = meta
            generation = currentTrackGeneration
        }

        if (trackChanged) {
            diagnose(
                "KG_DIAG metadata changed gen=$generation id=${songId.take(48)} " +
                    "key=${meta.trackKey.take(80)} duration=${meta.duration}"
            )
            KuGouLyricInfoPublisher.onTrackChanged(meta, generation)
            scheduleTrackChanged(meta, generation)
        } else {
            diagnoseDebug(
                "KG_DIAG metadata refreshed gen=$generation id=${songId.take(48)} " +
                    "key=${meta.trackKey.take(80)}"
            )
        }

        if (trySendCachedLyrics(meta, generation)) {
            return
        }
        if (tryBindPendingLyricsToCurrent("metadata")) {
            return
        }
        scheduleLocalLyricFileProbe(meta, generation)
    }

    private fun handleCarLyricMetadata(meta: MetadataData): Boolean {
        val snapshot = currentTrackSnapshot()
        val current = snapshot.metadata ?: return false
        if (snapshot.generation <= 0L || hasLyricReadyForGeneration(snapshot.generation)) {
            return false
        }
        if (!sameStableMedia(current, meta)) return false
        if (normalizeLocalLyricFileText(current.title) == normalizeLocalLyricFileText(meta.title)) {
            return false
        }
        if (current.artist.isNotBlank() &&
            meta.artist.isNotBlank() &&
            normalizeLocalLyricFileText(current.artist) != normalizeLocalLyricFileText(meta.artist)
        ) {
            return false
        }

        provider?.player?.sendText(meta.title)
        diagnoseDebug(
            "KG_DIAG car lyric fallback line gen=${snapshot.generation} " +
                "text=${meta.title.take(64)}"
        )
        return true
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
        if (bindLyricCandidateToCurrent(candidate, reason)) {
            return
        }
        rememberPendingLyricCandidate(candidate)
        diagnoseDebug(
            "KG_DIAG pending lyric candidate reason=$reason capturedGen=${candidate.capturedGeneration} " +
                "capturedId=${candidate.capturedSongId.orEmpty().take(48)}"
        )
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
            if (candidate.capturedGeneration + 1 == generation) {
                return 60
            }
        }
        return 0
    }

    private fun isCandidateNearCurrentTrack(
        candidate: LyricCandidate,
        snapshot: TrackSnapshot
    ): Boolean {
        val changedAt = snapshot.changedAtElapsed
        if (changedAt <= 0L) return false
        val startsBeforeWindowEnd =
            candidate.startedAtElapsed <= changedAt + LYRIC_CANDIDATE_BIND_WINDOW_MS
        val completesAfterWindowStart =
            candidate.completedAtElapsed >= changedAt - LYRIC_CANDIDATE_EARLY_WINDOW_MS
        return startsBeforeWindowEnd && completesAfterWindowStart
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
        val first = lyrics.first()
        if (!isMetadataLeadLyricLine(first, meta)) return lyrics
        diagnoseDebug(
            "KG_DIAG drop metadata lead lyric title=${meta.title.take(48)} " +
                "line=${first.text.orEmpty().take(80)}"
        )
        return lyrics.drop(1)
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
        if (lastEmittedSong == song && lastEmittedSongGeneration == trackGeneration) {
            diagnoseDebug("KG_DIAG skip duplicate lyricReady gen=$trackGeneration reason=$reason")
            return true
        }
        lastEmittedSong = song
        lastEmittedSongGeneration = trackGeneration
        provider?.player?.setSong(song)
        SaltLyricBridge.send(appContext, song, meta.mediaUri, trackGeneration)
        KuGouLyricInfoPublisher.onLyricReady(appContext, song, meta, trackGeneration)
        diagnose(
            "KG_DIAG send lyricReady gen=$trackGeneration reason=$reason " +
                "id=${song.id.orEmpty().take(48)} title=${song.name.orEmpty().take(64)} " +
                "lines=${song.lyrics?.size ?: 0}"
        )
        return true
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
        if (KUGOU_DIAGNOSTICS_ENABLED || Log.isLoggable(TAG, Log.DEBUG)) {
            YLog.debug(tag = TAG, msg = message)
        }
    }

    private fun diagnoseDebug(message: String) {
        if (KUGOU_DIAGNOSTICS_ENABLED || Log.isLoggable(TAG, Log.DEBUG)) {
            YLog.debug(tag = TAG, msg = message)
        }
    }
}
