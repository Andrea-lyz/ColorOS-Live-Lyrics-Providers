/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.SystemClock
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookContext
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KuGouPlayerHooker(private val hookContext: ProviderHookContext) {
    private val hookRuntime = hookContext.runtime
    private val hostContext = hookContext.application
    private val hostPackage = hookContext.packageName
    private val processName: String
        get() = hookContext.processName

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()
    private val generationPolicy = TrackGenerationPolicy()
    private val pendingCandidates = ArrayDeque<LyricCandidate>()
    private val localProbeDelaysMs = longArrayOf(0L, 220L, 760L)

    /**
     * v4.1 replacement for the 4.0 legacy hook objectExtra bridge: before and after of one
     * lyric load call run on the same thread inside a single API 102 interceptor, so a
     * ThreadLocal carries the per-call snapshot/path/started state.
     */
    private val lyricLoadState = ThreadLocal<LyricLoadState?>()

    @Volatile
    private var debugConfigAnnounced = false

    @Volatile
    private var currentTrack: TrackIdentity? = null

    @Volatile
    private var currentGeneration = 0L

    private var lastLyricReadyGeneration = 0L
    private var pendingLocalProbeJob: Job? = null
    private var pendingLocalProbeGeneration = 0L
    private var lastEmittedSignature = ""

    fun onHook() {
        // Process gating moved to the API 102 entry (detach before any hook is installed).
        if (!applyRuntimeAndDebug()) return
        KuGouDiagnostics.info(
            area = "bootstrap",
            event = "PROCESS_READY",
            process = processName,
            reason = hostPackage
        )
        hookMediaSession()
        hookLyricManager()
    }

    private fun applyRuntimeAndDebug(): Boolean {
        RuntimeModeResolver.notifyXposedHookActive()
        val resolution = RuntimeModeResolver.resolve(hostContext)
        if (!resolution.mode.isSupported) {
            KuGouDiagnostics.warn(
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
            provider = ProviderId.KUGOU,
            rootSource = hookContext.debugSource,
            frameworkSink = hookContext.frameworkSink
        )
        if (debugConfigAnnounced) return true
        debugConfigAnnounced = true
        KuGouDiagnostics.info(
            area = "bootstrap",
            event = "DEBUG_CONFIG_APPLIED",
            process = processName,
            reason = debug.reason,
            mode = resolution.mode
        )
        if (debug.enabled) {
            KuGouDiagnostics.debug(
                area = "bootstrap",
                event = "DEBUG_LOGGING_ENABLED",
                process = processName,
                reason = debug.reason,
                mode = resolution.mode
            )
        }
        return true
    }

    private fun hookLyricManager() {
        val method = runCatching {
            KuGouLyricManagerResolver.resolveLoadMethod(
                hookContext.application.applicationInfo.sourceDir,
                hookContext.classLoader
            )
        }.onFailure {
            KuGouDiagnostics.error(
                area = "hook",
                event = "LYRIC_MANAGER_RESOLVE_FAILED",
                process = processName,
                message = it.message,
                throwable = it
            )
        }.getOrNull()
        if (method == null) {
            KuGouDiagnostics.error(
                area = "hook",
                event = "LYRIC_MANAGER_MISSING",
                process = processName
            )
            return
        }
        hookRuntime.hook(method, "kugou.lyric.${method.declaringClass.name}#${method.name}") {
            before {
                val path = args.getOrNull(0) as? String ?: return@before
                lyricLoadState.set(LyricLoadState(currentSnapshot(), path, SystemClock.elapsedRealtime()))
            }
            after {
                val state = lyricLoadState.get()
                lyricLoadState.remove()
                val path = state?.path
                    ?: args.getOrNull(0) as? String
                    ?: return@after
                val snapshot = state?.snapshot ?: currentSnapshot()
                val startedAt = state?.startedAt ?: SystemClock.elapsedRealtime()
                val loadResult = result
                KuGouDiagnostics.debug(
                    area = "lyric",
                    event = "KUGOU_KRC_LOAD_CAPTURED",
                    generation = snapshot.generation,
                    message = "pathHash=${DiagnosticHasher.sha256(path)}"
                )
                scope.launch {
                    handleLyricLoad(loadResult, path, snapshot, startedAt)
                }
            }
        }
        KuGouDiagnostics.info(
            area = "hook",
            event = "LYRIC_MANAGER_HOOKED",
            process = processName,
            message = method.declaringClass.name + "#" + method.name
        )
    }

    private fun hookMediaSession() {
        runCatching {
            val setMetadata = MediaSession::class.java
                .getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            hookRuntime.hook(setMetadata, "kugou.session.MediaSession#setMetadata") {
                before {
                    if (KuGouLyricInfoPublisher.isSelfPublishing()) return@before
                    val session = instanceOrNull as? MediaSession
                    if (!KuGouPlayerConstants.isPrimarySessionTag(
                            KuGouLyricInfoPublisher.sessionTag(session)
                        )
                    ) {
                        return@before
                    }
                    val metadata = args.getOrNull(0) as? MediaMetadata ?: return@before
                    observeMetadata(metadata)
                    args[0] = KuGouLyricInfoPublisher.prepareHostMetadata(
                        session,
                        metadata,
                        hostPackage
                    )
                }
                after {
                    KuGouLyricInfoPublisher.onHostMetadataApplied()
                }
            }
        }.onFailure {
            KuGouDiagnostics.error(
                area = "session",
                event = "SESSION_HOOK_FAILED",
                process = processName,
                message = it.message,
                throwable = it
            )
            return
        }
        KuGouDiagnostics.info(
            area = "hook",
            event = "MEDIA_SESSION_HOOKED",
            process = processName
        )
    }

    private fun observeMetadata(metadata: MediaMetadata) {
        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        )
        if (title.isNullOrBlank()) return
        val artist = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        )
        if (KuGouPlayerConstants.isLite(hostPackage) &&
            currentTrack != null &&
            currentGeneration > 0L &&
            KuGouMetadataIdentityPolicy.looksLikeCarLyricDisplayMetadata(
                title = title,
                artist = artist,
                currentTitle = currentTrack?.title,
                currentArtist = currentTrack?.artist
            )
        ) {
            KuGouDiagnostics.debug(
                area = "identity",
                event = "CAR_LYRIC_IGNORED",
                generation = currentGeneration,
                trackHash = currentTrack?.let { DiagnosticHasher.sha256(it.buildStableKey()) },
                message = "titleChars=${title.length}"
            )
            return
        }
        val songId = KuGouOfficialLyricInfoEncoder.extractJsonString(
            metadata.getString(KuGouPlayerConstants.METADATA_KEY_LYRIC_INFO).orEmpty(),
            "songId"
        )
        val track = KuGouTrackIdentity.sanitize(
            hostPackage = hostPackage,
            title = title,
            artist = artist,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            mediaId = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.description.mediaId
            ),
            songIdFromLyricInfo = songId
        )
        val generation = generationPolicy.onTrackObserved(
            KuGouTrackIdentity.generationIdentity(track)
        )
        val changed: Boolean
        synchronized(stateLock) {
            // mediaId/songId enrichment is not a track transition. Generation is owned by the
            // stable title/artist/duration identity above; keep the latest full track only as the
            // publication/matching carrier.
            changed = generation != currentGeneration
            currentTrack = track
            currentGeneration = generation
            if (changed) {
                lastEmittedSignature = ""
                lastLyricReadyGeneration = 0L
            }
        }
        if (changed) {
            KuGouLyricInfoPublisher.onTrackChanged(generation)
            KuGouDiagnostics.info(
                area = "identity",
                event = "TRACK_BOUND",
                generation = generation,
                trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                reason = "metadata",
                message = "durationMs=${track.durationMs}"
            )
            if (!tryBindCachedOrPending(track, generation, "metadata")) {
                scheduleLocalLyricProbe(track, generation)
            }
        }
    }

    private suspend fun handleLyricLoad(
        result: Any?,
        path: String,
        snapshot: TrackSnapshot,
        startedAt: Long
    ) {
        val fromData = runCatching {
            KuGouLyricDataDecoder.decode(KuGouLyricDataDecoder.lyricDataFromResult(result))
        }.onFailure {
            KuGouDiagnostics.error(
                area = "lyric",
                event = "LYRIC_DATA_DECODE_FAILED",
                message = it.message,
                throwable = it
            )
        }.getOrDefault(emptyList())
        val lyrics = fromData.ifEmpty {
            val file = waitForReadableLyricFile(path)
            if (file != null) KuGouKrcFileDecoder.decodeFile(file) else emptyList()
        }
        if (lyrics.isEmpty()) {
            KuGouDiagnostics.debug(
                area = "lyric",
                event = "KUGOU_LYRIC_EMPTY",
                generation = snapshot.generation,
                message = "pathHash=${DiagnosticHasher.sha256(path)}"
            )
            return
        }
        KuGouDiagnostics.debug(
            area = "lyric",
            event = "KUGOU_LYRIC_PARSED",
            generation = snapshot.generation,
            message = "lines=${lyrics.size} pathHash=${DiagnosticHasher.sha256(path)} " +
                "source=${if (fromData.isNotEmpty()) "lyric-data" else "file"}"
        )
        handleCandidate(
            LyricCandidate(
                lyrics = lyrics,
                capturedId = snapshot.track?.id,
                capturedTitle = snapshot.track?.title,
                capturedArtist = snapshot.track?.artist,
                capturedGeneration = snapshot.generation,
                path = path,
                startedAtElapsed = startedAt,
                completedAtElapsed = SystemClock.elapsedRealtime(),
                source = if (fromData.isNotEmpty()) "lyric-data" else "krc-file"
            )
        )
    }

    private fun handleCandidate(candidate: LyricCandidate) {
        val current = currentSnapshot()
        val track = current.track
        if (track != null && isForeign(candidate, track)) {
            KuGouDiagnostics.debug(
                area = "lyric",
                event = "LYRIC_CANDIDATE_REJECTED",
                generation = current.generation,
                reason = "foreign",
                message = "pathHash=${DiagnosticHasher.sha256(candidate.path)}"
            )
            return
        }
        if (track != null && bindCandidate(candidate, track, current.generation)) {
            return
        }
        synchronized(stateLock) {
            pendingCandidates.removeAll { it.path == candidate.path }
            pendingCandidates.addLast(candidate)
            while (pendingCandidates.size > MAX_PENDING) {
                pendingCandidates.removeFirst()
            }
        }
        KuGouDiagnostics.debug(
            area = "lyric",
            event = "LYRIC_CANDIDATE_PENDING",
            generation = candidate.capturedGeneration,
            message = "pathHash=${DiagnosticHasher.sha256(candidate.path)}"
        )
    }

    private fun tryBindCachedOrPending(
        track: TrackIdentity,
        generation: Long,
        reason: String
    ): Boolean {
        KuGouLyricsCache.get(KuGouTrackIdentity.identityKeys(track))?.let { cached ->
            return emitLyrics(cached, track, generation, "cache-$reason")
        }
        val candidate = synchronized(stateLock) {
            pendingCandidates
                .asSequence()
                .filter { !isForeign(it, track) }
                .maxByOrNull { it.completedAtElapsed }
                ?.also { pendingCandidates.remove(it) }
        } ?: return false
        return bindCandidate(candidate, track, generation)
    }

    private fun bindCandidate(
        candidate: LyricCandidate,
        track: TrackIdentity,
        generation: Long
    ): Boolean {
        if (isForeign(candidate, track)) return false
        val capturedId = candidate.capturedId.orEmpty()
        val identityHit = capturedId.isNotBlank() &&
            KuGouTrackIdentity.identityKeys(track).contains(capturedId)
        val generationHit = candidate.capturedGeneration == generation ||
            candidate.capturedGeneration == 0L
        if (!identityHit && !generationHit) return false
        return emitLyrics(candidate.lyrics, track, generation, candidate.source)
    }

    private fun emitLyrics(
        lyrics: List<RichLyricLine>,
        track: TrackIdentity,
        generation: Long,
        source: String
    ): Boolean {
        val current = currentSnapshot()
        if (current.generation != generation || current.track?.id != track.id) {
            return false
        }
        val clean = KuGouOfficialLyricInfoEncoder.sanitize(lyrics, track)
        if (clean.isEmpty()) return false
        val signature = buildSignature(track, clean)
        synchronized(stateLock) {
            if (lastLyricReadyGeneration == generation && lastEmittedSignature == signature) {
                return true
            }
            lastLyricReadyGeneration = generation
            lastEmittedSignature = signature
            pendingLocalProbeJob?.cancel()
            pendingLocalProbeJob = null
            pendingLocalProbeGeneration = 0L
        }
        KuGouLyricsCache.put(KuGouTrackIdentity.identityKeys(track, listOf(track.id.orEmpty())), clean)
        val publication = KuGouPublication(track, clean, generation, source)
        KuGouLyricInfoPublisher.onLyricReady(publication)
        val replayed = KuGouLyricInfoPublisher.replayIfNeeded(hostPackage)
        KuGouDiagnostics.info(
            area = "publisher",
            event = if (replayed) "NATIVE_LYRICINFO_PATCHED" else "LYRIC_READY_WAITING_HOST",
            generation = generation,
            message = "source=$source lines=${clean.size} replay=$replayed"
        )
        return true
    }

    private fun scheduleLocalLyricProbe(track: TrackIdentity, generation: Long) {
        synchronized(stateLock) {
            if (pendingLocalProbeGeneration == generation) return
            pendingLocalProbeJob?.cancel()
            pendingLocalProbeGeneration = generation
            pendingLocalProbeJob = scope.launch {
                var elapsed = 0L
                for (delayMs in localProbeDelaysMs) {
                    if (delayMs > elapsed) {
                        delay(delayMs - elapsed)
                        elapsed = delayMs
                    }
                    if (currentSnapshot().generation != generation) return@launch
                    if (lastLyricReadyGeneration >= generation) return@launch
                    if (tryBindCachedOrPending(track, generation, "probe-$delayMs")) return@launch
                    if (probeLocalFile(track, generation)) return@launch
                }
            }
        }
    }

    private fun probeLocalFile(track: TrackIdentity, generation: Long): Boolean {
        val file = findBestLocalLyricFile(track) ?: return false
        val lyrics = runCatching { KuGouKrcFileDecoder.decodeFile(file) }.getOrDefault(emptyList())
        if (lyrics.isEmpty()) return false
        KuGouDiagnostics.debug(
            area = "lyric",
            event = "LOCAL_FILE_HIT",
            generation = generation,
            message = "pathHash=${DiagnosticHasher.sha256(file.path)}"
        )
        return emitLyrics(lyrics, track, generation, "local-file")
    }

    private fun findBestLocalLyricFile(track: TrackIdentity): File? {
        val title = normalizeFileText(track.title)
        if (title.isBlank()) return null
        val artist = normalizeFileText(track.artist)
        var bestFile: File? = null
        var bestScore = 0
        var bestModified = 0L
        candidateLyricDirectories().forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (!file.isFile || file.length() <= 0L) return@forEach
                if (file.extension.lowercase() !in setOf("krc", "lyc", "lrc", "txt")) return@forEach
                val identity = KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(file.path)
                if (identity != null &&
                    KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
                        identity.artist,
                        identity.title,
                        track.title,
                        track.artist
                    )
                ) {
                    return@forEach
                }
                val stem = normalizeFileText(file.nameWithoutExtension)
                if (!stem.contains(title)) return@forEach
                var score = 70
                if (artist.isNotBlank() && stem.contains(artist)) score += 25
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

    private fun candidateLyricDirectories(): List<File> {
        return listOf(
            File(hostContext.filesDir, "kugou/lyrics"),
            File(hostContext.filesDir, "lyrics"),
            File(hostContext.cacheDir, "kugou/lyrics"),
            File(hostContext.cacheDir, "lyrics")
        ).filter { it.isDirectory }
    }

    private fun isForeign(candidate: LyricCandidate, track: TrackIdentity): Boolean {
        if (KuGouOriginalLyricCandidatePolicy.hasForeignLeadingMetadata(
                candidate.capturedId,
                track.id,
                candidate.lyrics.firstOrNull()?.text,
                track.title,
                track.artist
            )
        ) {
            return true
        }
        val identity = KuGouOriginalLyricCandidatePolicy.fileIdentityFromPath(candidate.path)
            ?: return false
        return KuGouOriginalLyricCandidatePolicy.isForeignFileIdentity(
            identity.artist,
            identity.title,
            track.title,
            track.artist
        )
    }

    private suspend fun waitForReadableLyricFile(path: String): File? {
        val file = File(path)
        repeat(5) { attempt ->
            if (file.exists() && file.length() > 0L) return file
            if (attempt < 4) delay(80L)
        }
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    private fun currentSnapshot(): TrackSnapshot {
        return synchronized(stateLock) {
            TrackSnapshot(currentTrack, currentGeneration)
        }
    }

    private fun buildSignature(track: TrackIdentity, lines: List<RichLyricLine>): String {
        val first = lines.firstOrNull()
        val last = lines.lastOrNull()
        return listOf(
            track.id.orEmpty(),
            lines.size.toString(),
            first?.begin?.toString().orEmpty(),
            first?.text.orEmpty(),
            last?.text.orEmpty()
        ).joinToString("|")
    }

    private fun normalizeFileText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return buildString(value.length) {
            value.trim().lowercase().forEach { ch ->
                if (ch.isLetterOrDigit()) append(ch)
            }
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private data class TrackSnapshot(
        val track: TrackIdentity?,
        val generation: Long
    )

    private data class LyricLoadState(
        val snapshot: TrackSnapshot,
        val path: String,
        val startedAt: Long
    )

    private data class LyricCandidate(
        val lyrics: List<RichLyricLine>,
        val capturedId: String?,
        val capturedTitle: String?,
        val capturedArtist: String?,
        val capturedGeneration: Long,
        val path: String,
        val startedAtElapsed: Long,
        val completedAtElapsed: Long,
        val source: String
    )

    private companion object {
        const val MAX_PENDING = 8
    }
}
