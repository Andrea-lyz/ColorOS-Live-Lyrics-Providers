/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher
import io.github.andrealtb.coloroslyrics.provider.core.session.PlaybackStateTranslationToggle
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookContext
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class QishuiPlayerHooker(private val hookContext: ProviderHookContext) {
    private data class ResolutionRequest(
        val track: TrackIdentity,
        val generation: Long,
        val attempt: Int,
        val token: Long
    )

    private data class NegativeHit(val expiresAtElapsedMillis: Long)

    private data class CommitMarker(
        val generation: Long,
        val sessionIdentity: Int,
        val publicationHash: Int
    )

    private val hookRuntime = hookContext.runtime
    private val hostContext = hookContext.application
    private val hostPackage = hookContext.packageName
    private val hostVersion = hookContext.hostVersion

    private val sessions = QishuiMediaSessionRegistry()
    private val generationPolicy = TrackGenerationPolicy()
    private val cacheResolver = QishuiCacheResolver(hostContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolverExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Qishui-LyricResolver").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val publicationLock = Any()
    private val resolutionLock = Any()
    private var replaySnapshot: QishuiReplaySnapshot? = null
    private var commitMarker: CommitMarker? = null
    private var resolutionToken = 0L
    private var pendingResolution: ResolutionRequest? = null
    private var translationActionInjectionLogged = false
    @Volatile
    private var translationGeneration = 0L
    @Volatile
    private var translationCount = 0
    private var scheduledPlaybackRefreshGeneration = 0L

    private val lyricCache = object : LinkedHashMap<String, QishuiPublication>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, QishuiPublication>?
        ): Boolean = size > 32
    }
    private val negativeCache = object : LinkedHashMap<String, NegativeHit>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, NegativeHit>?
        ): Boolean = size > 32
    }

    private val officialLyrics = QishuiOfficialLyricsHook(
        authorityProvider = {
            generationPolicy.currentTrack?.let { current ->
                generationPolicy.generation.takeIf { it > 0L }?.let { generation ->
                    QishuiTrackAuthority(current, generation)
                }
            }
        },
        onPublication = { publication, generation ->
            runOnMain { acceptPublication(publication, generation, true) }
        }
    )

    fun onHook() {
        // Process gating moved to the API 102 entry (detach before any hook is installed).
        val resolution = RuntimeModeResolver.resolve(hostContext)
        if (!resolution.mode.isSupported) {
            logWarning("bootstrap", "HOOK_DISABLED", resolution.markerSource)
            return
        }
        val debug = ProviderDebugConfig.applyDiagnostics(
            mode = resolution.mode,
            provider = ProviderId.QISHUI,
            rootSource = hookContext.debugSource,
            frameworkSink = hookContext.frameworkSink
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = "bootstrap",
                event = "DEBUG_CONFIG_APPLIED",
                mode = resolution.mode,
                process = resolution.processName,
                reason = debug.reason
            )
        )
        if (debug.enabled) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "bootstrap",
                    event = "DEBUG_LOGGING_ENABLED",
                    mode = resolution.mode,
                    process = resolution.processName,
                    reason = debug.reason
                )
            )
        }
        installSessionHooks()
        officialLyrics.install(hookRuntime, hookContext.classLoader)
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = "bootstrap",
                event = "PROCESS_READY",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEachIndexed { index, constructor ->
                hookRuntime.hook(constructor, "qishui.session.MediaSession#ctor$index") {
                    after {
                        (instanceOrNull as? MediaSession)?.let { session ->
                            sessions.onConstructed(
                                session,
                                QishuiMediaSessionRegistry.constructorTag(args)
                            )
                        }
                    }
                }
            }

            hookRuntime.hook(
                type.getDeclaredMethod("setMetadata", MediaMetadata::class.java),
                "qishui.session.MediaSession#setMetadata"
            ) {
                before {
                    if (sessions.isModuleWrite()) return@before
                    val session = instanceOrNull as? MediaSession ?: return@before
                    if (sessions.isCastSession(session)) return@before
                    val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                    val track = QishuiTrackMetadata.fromMetadata(incoming) ?: return@before
                    sessions.onHostMetadata(session, track, incoming)
                    val generation = observeTrack(track)
                    var outgoing = attachReplay(session, incoming, track, generation)
                    if (outgoing === incoming &&
                        QishuiTrackMetadata.isModuleOwnedLyricInfo(
                            incoming.getString(QishuiPlayerConstants.METADATA_KEY_LYRIC_INFO)
                        ) &&
                        synchronized(publicationLock) { replaySnapshot == null }
                    ) {
                        outgoing = QishuiMetadataCopy.stripModuleLyricInfo(incoming)
                    }
                    if (outgoing !== incoming) args[0] = outgoing
                    runOnMain { requestResolution(track, generation) }
                }
            }

            hookRuntime.hook(
                type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java),
                "qishui.session.MediaSession#setPlaybackState"
            ) {
                before {
                    val original = args.getOrNull(0) as? PlaybackState ?: return@before
                    val exposeTranslation = QishuiTranslationActionPolicy.shouldExpose(
                        generationPolicy.generation,
                        translationGeneration,
                        translationCount
                    )
                    val patched = if (exposeTranslation) {
                        PlaybackStateTranslationToggle.prependPublicAction(
                            original,
                            hostContext
                        )
                    } else {
                        PlaybackStateTranslationToggle.removePublicAction(original)
                    }
                    if (patched !== original) {
                        args[0] = patched
                        if (exposeTranslation && !translationActionInjectionLogged) {
                            translationActionInjectionLogged = true
                            StructuredDiagnostics.logInfo(
                                DiagnosticEvent(
                                    component = QishuiPlayerConstants.COMPONENT,
                                    area = "session",
                                    event = "TRANSLATION_ACTION_INJECTED",
                                    process = hookContext.packageName
                                )
                            )
                        } else if (!exposeTranslation) {
                            StructuredDiagnostics.logInfo(
                                DiagnosticEvent(
                                    component = QishuiPlayerConstants.COMPONENT,
                                    area = "session",
                                    event = "TRANSLATION_ACTION_REMOVED",
                                    generation = generationPolicy.generation,
                                    reason = "translation-count=" + translationCount
                                )
                            )
                        }
                    }
                }
                after {
                    val session = instanceOrNull as? MediaSession ?: return@after
                    val state = (args.getOrNull(0) as? PlaybackState)?.state
                        ?: PlaybackState.STATE_NONE
                    sessions.onPlaybackState(session, state)
                    drainReplay()
                    val current = generationPolicy.currentTrack
                    val generation = generationPolicy.generation
                    if (current != null && generation > 0L) {
                        runOnMain { requestResolution(current, generation) }
                    }
                }
            }

            hookRuntime.hook(
                type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType),
                "qishui.session.MediaSession#setActive"
            ) {
                after {
                    val session = instanceOrNull as? MediaSession ?: return@after
                    sessions.onActive(session, args.getOrNull(0) as? Boolean ?: false)
                    drainReplay()
                }
            }

            hookRuntime.hook(type.getDeclaredMethod("release"), "qishui.session.MediaSession#release") {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    sessions.onReleased(session)
                }
            }
        }.onFailure { throwable ->
            StructuredDiagnostics.logError(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "SESSION_HOOK_FAILED",
                    reason = throwable.javaClass.simpleName
                ),
                throwable = throwable
            )
        }
    }

    private fun observeTrack(track: TrackIdentity): Long {
        val previous = generationPolicy.currentTrack
        val generation = generationPolicy.onTrackObserved(track)
        if (previous == null || !TrackIdentityPolicy.isSameTrack(previous, track)) {
            synchronized(publicationLock) {
                replaySnapshot = null
                commitMarker = null
            }
            translationGeneration = generation
            translationCount = 0
            scheduledPlaybackRefreshGeneration = 0L
            synchronized(resolutionLock) {
                nextResolutionTokenLocked()
                pendingResolution = null
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "track",
                    event = "TRACK_BOUND",
                    generation = generation,
                    trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                    reason = "metadata",
                    durationMs = track.durationMs
                )
            )
        }
        return generation
    }

    private fun requestResolution(observedTrack: TrackIdentity, generation: Long) {
        if (!generationPolicy.isGenerationValid(generation)) return
        val current = generationPolicy.currentTrack ?: return
        if (!TrackIdentityPolicy.isSameTrack(current, observedTrack)) return
        val id = current.id?.trim()?.takeIf(String::isNotEmpty) ?: return
        val replay = synchronized(publicationLock) { replaySnapshot }
        if (replay?.generation == generation &&
            TrackIdentityPolicy.isSameTrack(replay.publication.track, current)
        ) {
            return
        }
        synchronized(lyricCache) {
            lyricCache[id]
        }?.let { cached ->
            acceptPublication(
                cached.copy(track = QishuiTrackMetadata.mergeMetadataFirst(current, cached.track)),
                generation,
                false
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val negative = synchronized(negativeCache) {
            negativeCache[id]?.also { hit ->
                if (hit.expiresAtElapsedMillis <= now) negativeCache.remove(id)
            }
        }
        if (negative != null && negative.expiresAtElapsedMillis > now) return
        val request = synchronized(resolutionLock) {
            val pending = pendingResolution
            if (pending?.generation == generation && pending.track.id == current.id) return
            ResolutionRequest(
                track = current,
                generation = generation,
                attempt = 0,
                token = nextResolutionTokenLocked()
            ).also { pendingResolution = it }
        }
        startResolution(request)
    }

    private fun startResolution(request: ResolutionRequest) {
        resolverExecutor.execute {
            val publication = runCatching { cacheResolver.resolve(request.track) }
                .onFailure { throwable ->
                    logWarning(
                        "cache",
                        "LYRIC_RESOLUTION_FAILED",
                        throwable.javaClass.simpleName
                    )
                }
                .getOrNull()
            mainHandler.post {
                handleResolutionResult(request, publication)
            }
        }
    }

    private fun handleResolutionResult(
        request: ResolutionRequest,
        publication: QishuiPublication?
    ) {
        val valid = synchronized(resolutionLock) {
            val pending = pendingResolution
            pending?.token == request.token &&
                pending.generation == request.generation &&
                pending.attempt == request.attempt
        } &&
            generationPolicy.isGenerationValid(request.generation) &&
            TrackIdentityPolicy.isSameTrack(generationPolicy.currentTrack, request.track)
        if (!valid) {
            logDebug("cache", "STALE_RESOLUTION_DROPPED", request.track.id.orEmpty())
            return
        }
        if (publication != null) {
            synchronized(resolutionLock) {
                pendingResolution = null
                nextResolutionTokenLocked()
            }
            acceptPublication(publication, request.generation, false)
            return
        }
        scheduleNextAttempt(request)
    }

    private fun scheduleNextAttempt(request: ResolutionRequest) {
        val nextAttempt = request.attempt + 1
        val delay = QishuiResolutionPolicy.delayBeforeAttempt(nextAttempt)
        if (nextAttempt >= QishuiResolutionPolicy.MAX_ATTEMPTS || delay == null) {
            request.track.id?.let { id ->
                synchronized(negativeCache) {
                    negativeCache[id] = NegativeHit(
                        SystemClock.elapsedRealtime() +
                            QishuiResolutionPolicy.NEGATIVE_CACHE_TTL_MS
                    )
                }
            }
            synchronized(resolutionLock) {
                pendingResolution = null
                nextResolutionTokenLocked()
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "cache",
                    event = "LYRIC_RESOLUTION_EXHAUSTED",
                    generation = request.generation,
                    reason = "attempts=" + QishuiResolutionPolicy.MAX_ATTEMPTS
                )
            )
            return
        }
        val scheduled = request.copy(attempt = nextAttempt)
        synchronized(resolutionLock) {
            pendingResolution = scheduled
        }
        mainHandler.postDelayed(
            {
                val stillValid = synchronized(resolutionLock) {
                    val pending = pendingResolution
                    pending?.token == scheduled.token &&
                        pending.attempt == scheduled.attempt &&
                        pending.generation == scheduled.generation
                } &&
                    generationPolicy.isGenerationValid(scheduled.generation) &&
                    TrackIdentityPolicy.isSameTrack(
                        generationPolicy.currentTrack,
                        scheduled.track
                    )
                if (stillValid) startResolution(scheduled)
            },
            delay
        )
    }

    private fun acceptPublication(
        incoming: QishuiPublication,
        generation: Long,
        internalSource: Boolean
    ) {
        val current = generationPolicy.currentTrack ?: return
        if (!generationPolicy.isGenerationValid(generation) ||
            !TrackIdentityPolicy.isSameTrack(current, incoming.track)
        ) {
            logDebug("publisher", "STALE_PUBLICATION_DROPPED", incoming.track.id.orEmpty())
            return
        }
        val publication = incoming.copy(
            track = QishuiTrackMetadata.mergeMetadataFirst(current, incoming.track)
        )
        val availableTranslations =
            publication.lines.count { !it.secondary.isNullOrBlank() }
        val previous = synchronized(publicationLock) { replaySnapshot }
        if (previous?.generation == generation &&
            previous.publication.track == publication.track &&
            previous.publication.lines == publication.lines
        ) {
            return
        }
        if (internalSource) {
            synchronized(resolutionLock) {
                pendingResolution = null
                nextResolutionTokenLocked()
            }
        }
        publication.track.id?.let { id ->
            synchronized(lyricCache) { lyricCache[id] = publication }
            synchronized(negativeCache) { negativeCache.remove(id) }
        }
        synchronized(publicationLock) {
            replaySnapshot = QishuiReplaySnapshot(publication, generation)
        }
        val translationAvailabilityChanged =
            translationGeneration != generation || translationCount != availableTranslations
        translationGeneration = generation
        translationCount = availableTranslations
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = "lyrics",
                event = "INTERNAL_LYRIC_READY",
                generation = generation,
                reason = publication.sourceName,
                message = "lines=" + publication.lines.size +
                    " translations=" + availableTranslations
            )
        )
        drainReplay()
        if (translationAvailabilityChanged) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "session",
                    event = "TRANSLATION_AVAILABILITY_CHANGED",
                    generation = generation,
                    reason = if (availableTranslations > 0) "available" else "unavailable",
                    message = "translationCount=" + availableTranslations
                )
            )
            scheduleHostPlaybackStateRefresh(
                generation = generation,
                delayMillis = 0L,
                reason = "translation-availability"
            )
        }
        if (scheduledPlaybackRefreshGeneration != generation) {
            scheduledPlaybackRefreshGeneration = generation
            scheduleHostPlaybackStateRefresh(
                generation = generation,
                delayMillis = HOST_CLOCK_REFRESH_DELAY_MS,
                reason = "post-track-bind-clock"
            )
        }
    }

    private fun attachReplay(
        session: MediaSession,
        metadata: MediaMetadata,
        track: TrackIdentity,
        generation: Long
    ): MediaMetadata {
        val snapshot = synchronized(publicationLock) { replaySnapshot } ?: return metadata
        if (snapshot.generation != generation ||
            !generationPolicy.isGenerationValid(snapshot.generation) ||
            !TrackIdentityPolicy.isSameTrack(snapshot.publication.track, track)
        ) {
            return metadata
        }
        if (QishuiTrackMetadata.isModuleOwnedLyricInfo(
                metadata.getString(QishuiPlayerConstants.METADATA_KEY_LYRIC_INFO)
            )
        ) {
            return metadata
        }
        val patched = QishuiNativePublisher.buildReplayMetadata(
            metadata,
            snapshot,
            generationPolicy,
            hostPackage
        ).second ?: return metadata
        synchronized(publicationLock) {
            commitMarker = markerFor(snapshot, session)
        }
        return patched
    }

    private fun drainReplay() {
        val snapshot = synchronized(publicationLock) { replaySnapshot } ?: return
        if (!generationPolicy.isGenerationValid(snapshot.generation)) return
        val session = sessions.resolve(snapshot.publication.track) ?: return
        val marker = markerFor(snapshot, session)
        if (synchronized(publicationLock) { commitMarker == marker }) return
        val result = QishuiNativePublisher.publish(
            session = session,
            publication = snapshot.publication,
            generation = snapshot.generation,
            generationPolicy = generationPolicy,
            registry = sessions,
            hostPackage = hostPackage
        )
        if (result.isPublished) {
            synchronized(publicationLock) {
                commitMarker = marker
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "publisher",
                    event = "NATIVE_LYRIC_INFO_COMMITTED",
                    generation = snapshot.generation,
                    reason = snapshot.publication.sourceName
                )
            )
        } else if (result != NativeLyricInfoPublisher.Result.INVALID_INPUT) {
            logWarning("publisher", "NATIVE_LYRIC_INFO_REJECTED", result.name)
        }
    }

    private fun scheduleHostPlaybackStateRefresh(
        generation: Long,
        delayMillis: Long,
        reason: String
    ) {
        mainHandler.postDelayed(
            {
                if (!generationPolicy.isGenerationValid(generation)) return@postDelayed
                val current = generationPolicy.currentTrack ?: return@postDelayed
                val refreshed = officialLyrics.refreshPlaybackState(
                    QishuiTrackAuthority(current, generation),
                    reason
                )
                if (!refreshed) {
                    logDebug(
                        "session",
                        "HOST_PLAYBACK_STATE_REFRESH_SKIPPED",
                        reason
                    )
                }
            },
            delayMillis
        )
    }

    private fun markerFor(
        snapshot: QishuiReplaySnapshot,
        session: MediaSession
    ): CommitMarker = CommitMarker(
        generation = snapshot.generation,
        sessionIdentity = System.identityHashCode(session),
        publicationHash = 31 * snapshot.publication.track.hashCode() +
            snapshot.publication.lines.hashCode()
    )

    private fun nextResolutionTokenLocked(): Long {
        resolutionToken = if (resolutionToken == Long.MAX_VALUE) 1L else resolutionToken + 1L
        return resolutionToken
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun logDebug(area: String, event: String, reason: String) {
        StructuredDiagnostics.logDebug(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = area,
                event = event,
                reason = reason
            )
        )
    }

    private fun logWarning(area: String, event: String, reason: String) {
        StructuredDiagnostics.logWarning(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = area,
                event = event,
                reason = reason
            )
        )
    }

    companion object {
        private const val HOST_CLOCK_REFRESH_DELAY_MS = 700L
    }
}
