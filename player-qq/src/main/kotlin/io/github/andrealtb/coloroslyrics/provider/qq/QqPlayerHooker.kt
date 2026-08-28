/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import android.media.MediaMetadata
import android.media.session.MediaSession
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.config.YukiHookDebugSource
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

class QqPlayerHooker(
    private val hostPackage: String
) : YukiBaseHooker() {

    private val stateLock = Any()
    private val generationPolicy = TrackGenerationPolicy()

    @Volatile
    private var debugConfigAnnounced = false

    @Volatile
    private var currentTrack: TrackIdentity? = null

    @Volatile
    private var currentGeneration = 0L

    private var lastEmittedSignature = ""
    private var lastLyricReadyGeneration = 0L
    private var cachedTranslationBySongId = linkedMapOf<String, Any>()

    override fun onHook() {
        if (!QqProcessPolicy.shouldHook(hostPackage, processName)) {
            QqDiagnostics.debug(
                area = "bootstrap",
                event = "PROCESS_SKIPPED",
                process = processName,
                reason = hostPackage
            )
            return
        }
        if (!applyRuntimeAndDebug()) return
        QqDiagnostics.info(
            area = "bootstrap",
            event = "PROCESS_READY",
            process = processName,
            reason = hostPackage
        )
        hookMediaSession()
        hookOnLoadSuc()
        hookSeedlingLyricInfo()
    }

    private fun applyRuntimeAndDebug(): Boolean {
        RuntimeModeResolver.notifyXposedHookActive()
        val hostContext = appContext
        val resolution = RuntimeModeResolver.resolve(hostContext)
        if (!resolution.mode.isSupported) {
            QqDiagnostics.warn(
                area = "bootstrap",
                event = "HOOK_DISABLED",
                process = resolution.processName,
                reason = resolution.markerSource,
                mode = resolution.mode
            )
            return false
        }
        if (hostContext == null) return true
        val debug = ProviderDebugConfig.applyDiagnostics(
            mode = resolution.mode,
            provider = ProviderId.QQ,
            rootSource = YukiHookDebugSource.create(hostContext)
        )
        if (debugConfigAnnounced) return true
        debugConfigAnnounced = true
        QqDiagnostics.info(
            area = "bootstrap",
            event = "DEBUG_CONFIG_APPLIED",
            process = processName,
            reason = debug.reason,
            mode = resolution.mode
        )
        if (debug.enabled) {
            QqDiagnostics.debug(
                area = "bootstrap",
                event = "DEBUG_LOGGING_ENABLED",
                process = processName,
                reason = debug.reason,
                mode = resolution.mode
            )
        }
        return true
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setMetadata"
                parameters(MediaMetadata::class.java)
            }.hook {
                before {
                    if (QqLyricInfoPublisher.isSelfPublishing()) return@before
                    val session = instance as? MediaSession ?: return@before
                    val metadata = args[0] as? MediaMetadata ?: return@before
                    observeMetadata(metadata)
                    args[0] = QqLyricInfoPublisher.prepareHostMetadata(
                        session,
                        metadata,
                        hostPackage
                    )
                }
                after {
                    QqLyricInfoPublisher.onHostMetadataApplied()
                }
            }
        }
        QqDiagnostics.info(
            area = "hook",
            event = "MEDIA_SESSION_HOOKED",
            process = processName
        )
    }

    private fun hookOnLoadSuc() {
        val method = runCatching {
            QqLyricHookResolver.resolveOnLoadSuc(appClassLoader!!, appInfo.sourceDir)
        }.onFailure {
            QqDiagnostics.error(
                area = "hook",
                event = "ON_LOAD_SUC_RESOLVE_FAILED",
                process = processName,
                message = it.message,
                throwable = it
            )
        }.getOrNull()
        if (method == null) {
            QqDiagnostics.error(
                area = "hook",
                event = "ON_LOAD_SUC_MISSING",
                process = processName
            )
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val bean = param.args.getOrNull(0) ?: return
                val (track, models) = QqSongInfoReader.readFromLoadBean(bean)
                bindTrack(track, "load-bean")
                if (models.translation != null && !track.id.isNullOrBlank()) {
                    rememberTranslation(track.id!!, models.translation)
                }
                val primary = QqLyricModelDecoder.decodePrimary(models.primary)
                val translation = QqLyricModelDecoder.decodeTranslation(models.translation)
                emitLyrics(
                    QqLyricModelDecoder.mergeTranslation(primary, translation),
                    currentSnapshot().track ?: track,
                    "load-bean"
                )
            }
        })
        QqDiagnostics.info(
            area = "hook",
            event = "ON_LOAD_SUC_HOOKED",
            process = processName,
            message = method.declaringClass.name + "#" + method.name
        )
    }

    private fun hookSeedlingLyricInfo() {
        val method = runCatching {
            QqLyricHookResolver.resolveSeedlingMethod(appInfo.sourceDir, appClassLoader!!)
        }.onFailure {
            QqDiagnostics.error(
                area = "hook",
                event = "SEEDLING_RESOLVE_FAILED",
                process = processName,
                message = it.message,
                throwable = it
            )
        }.getOrNull()
        if (method == null) {
            QqDiagnostics.error(
                area = "hook",
                event = "SEEDLING_MISSING",
                process = processName
            )
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val builder = param.args.getOrNull(0)
                val songInfo = param.args.getOrNull(1)
                val lyric = param.args.getOrNull(2)
                val track = QqSongInfoReader.read(songInfo)
                bindTrack(track, "seedling")
                val cachedTrans = track.id?.let { cachedTranslationBySongId[it] }
                val primary = QqLyricModelDecoder.decodePrimary(lyric)
                val translation = QqLyricModelDecoder.decodeTranslation(cachedTrans)
                val lines = QqLyricModelDecoder.mergeTranslation(primary, translation)
                val snapshot = currentSnapshot()
                val bound = snapshot.track ?: track
                emitLyrics(lines, bound, "seedling")
                val publication = synchronized(stateLock) {
                    if (snapshot.generation == currentGeneration) {
                        QqPublication(bound, lines, currentGeneration, "seedling")
                    } else {
                        null
                    }
                }
                if (publication != null && builder != null) {
                    val existing = QqCompatMetadata.readLyricInfo(builder)
                    val encoded = QqOfficialLyricInfoEncoder.encode(
                        track = publication.track,
                        lines = publication.lines,
                        trackGeneration = publication.generation,
                        hostPackage = hostPackage,
                        existingLyricInfo = existing
                    )
                    if (encoded != null) {
                        QqCompatMetadata.putLyricInfo(builder, encoded.value)
                        QqDiagnostics.info(
                            area = "publisher",
                            event = "NATIVE_LYRICINFO_PATCHED",
                            generation = publication.generation,
                            payloadChars = encoded.value.length,
                            message = "source=seedling raw=${encoded.rawLyric.isNotBlank()} " +
                                "translation=${encoded.translationLyric.isNotBlank()}"
                        )
                    }
                }
            }
        })
        QqDiagnostics.info(
            area = "hook",
            event = "SEEDLING_HOOKED",
            process = processName,
            message = method.declaringClass.name + "#" + method.name
        )
    }

    private fun observeMetadata(metadata: MediaMetadata) {
        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        )
        if (title.isNullOrBlank()) return
        val songId = QqOfficialLyricInfoEncoder.extractJsonString(
            metadata.getString(QqPlayerConstants.METADATA_KEY_LYRIC_INFO).orEmpty(),
            "songId"
        )
        bindTrack(
            TrackIdentity(
                id = songId,
                title = title,
                artist = firstNonBlank(
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ),
            "metadata"
        )
    }

    private fun bindTrack(track: TrackIdentity, reason: String) {
        if (track.isBlank) return
        val generation = generationPolicy.onTrackObserved(track)
        val changed: Boolean
        synchronized(stateLock) {
            changed = generation != currentGeneration || currentTrack?.id != track.id
            currentTrack = mergeTrack(currentTrack, track)
            currentGeneration = generation
            if (changed) {
                lastEmittedSignature = ""
                lastLyricReadyGeneration = 0L
            }
        }
        if (changed) {
            QqLyricInfoPublisher.onTrackChanged(generation)
            QqDiagnostics.info(
                area = "identity",
                event = "TRACK_BOUND",
                generation = generation,
                trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                reason = reason,
                message = "durationMs=${track.durationMs}"
            )
        }
    }

    private fun emitLyrics(
        lines: List<RichLyricLine>,
        track: TrackIdentity,
        source: String
    ): Boolean {
        if (lines.isEmpty() || track.isBlank) return false
        val snapshot = currentSnapshot()
        if (snapshot.generation <= 0L) return false
        val boundId = snapshot.track?.id
        if (!track.id.isNullOrBlank() &&
            !boundId.isNullOrBlank() &&
            track.id != boundId
        ) {
            QqDiagnostics.debug(
                area = "lyric",
                event = "LYRIC_CANDIDATE_REJECTED",
                generation = snapshot.generation,
                trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                reason = "foreign",
                message = "identity-mismatch"
            )
            return false
        }
        val signature = buildSignature(track, lines)
        synchronized(stateLock) {
            if (lastLyricReadyGeneration == snapshot.generation && lastEmittedSignature == signature) {
                return true
            }
            lastLyricReadyGeneration = snapshot.generation
            lastEmittedSignature = signature
        }
        val publication = QqPublication(track, lines, snapshot.generation, source)
        QqLyricInfoPublisher.onLyricReady(publication)
        val replayed = QqLyricInfoPublisher.replayIfNeeded(hostPackage)
        QqDiagnostics.info(
            area = "publisher",
            event = if (replayed) "NATIVE_LYRICINFO_PATCHED" else "LYRIC_READY_WAITING_HOST",
            generation = snapshot.generation,
            message = "source=$source lines=${lines.size} " +
                "translated=${lines.count { !it.secondary.isNullOrBlank() }} replay=$replayed"
        )
        return true
    }

    private fun rememberTranslation(songId: String, translation: Any) {
        synchronized(stateLock) {
            cachedTranslationBySongId[songId] = translation
            while (cachedTranslationBySongId.size > 8) {
                val oldest = cachedTranslationBySongId.keys.first()
                cachedTranslationBySongId.remove(oldest)
            }
        }
    }

    private fun currentSnapshot(): TrackSnapshot {
        return synchronized(stateLock) {
            TrackSnapshot(currentTrack, currentGeneration)
        }
    }

    private fun mergeTrack(current: TrackIdentity?, incoming: TrackIdentity): TrackIdentity {
        if (current == null) return incoming
        return TrackIdentity(
            id = firstNonBlank(incoming.id, current.id),
            title = firstNonBlank(incoming.title, current.title),
            artist = firstNonBlank(incoming.artist, current.artist),
            album = firstNonBlank(incoming.album, current.album),
            durationMs = if (incoming.durationMs > 0L) incoming.durationMs else current.durationMs
        )
    }

    private fun buildSignature(track: TrackIdentity, lines: List<RichLyricLine>): String {
        val first = lines.firstOrNull()
        val last = lines.lastOrNull()
        return listOf(
            track.id.orEmpty(),
            lines.size.toString(),
            first?.begin?.toString().orEmpty(),
            first?.text.orEmpty(),
            last?.text.orEmpty(),
            lines.count { !it.secondary.isNullOrBlank() }.toString()
        ).joinToString("|")
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private data class TrackSnapshot(
        val track: TrackIdentity?,
        val generation: Long
    )
}
