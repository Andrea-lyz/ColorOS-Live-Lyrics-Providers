/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.config.YukiHookDebugSource
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MetrolistPlayerHooker(
    private val hookContext: Context,
    private val hostPackage: String,
    private val hostVersion: String?
) : YukiBaseHooker() {

    private val sessions = MetrolistMediaSessionRegistry()
    private val publicationLock = Any()
    private val pendingStore = MetrolistPendingPublicationStore()
    private val fetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var replaySnapshot: MetrolistReplaySnapshot? = null

    @Volatile
    private var fetchGeneration: Long? = null

    @Volatile
    private var fetchJob: Job? = null

    @Volatile
    private var hookedMusicService: Any? = null

    private val generationController = MetrolistHostGenerationController { _, _ ->
        fetchJob?.cancel()
        fetchJob = null
        fetchGeneration = null
        synchronized(publicationLock) {
            replaySnapshot = null
            val droppedPending = pendingStore.peek() != null
            pendingStore.clear()
            if (droppedPending) {
                logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generationPolicy.generation)
            }
        }
    }

    private val generationPolicy: TrackGenerationPolicy
        get() = generationController.policy

    override fun onHook() {
        val resolution = RuntimeModeResolver.resolve(hookContext)
        if (!resolution.mode.isSupported) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = "provider/metrolist",
                    area = "bootstrap",
                    event = "HOOK_DISABLED",
                    mode = resolution.mode,
                    process = resolution.processName,
                    reason = resolution.markerSource
                )
            )
            return
        }

        val debug = ProviderDebugConfig.applyDiagnostics(
            mode = resolution.mode,
            provider = ProviderId.METROLIST,
            rootSource = YukiHookDebugSource.create(hookContext)
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/metrolist",
                area = "bootstrap",
                event = "DEBUG_CONFIG_APPLIED",
                mode = resolution.mode,
                process = hookContext.packageName,
                reason = debug.reason
            )
        )
        if (debug.enabled) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = "provider/metrolist",
                    area = "bootstrap",
                    event = "DEBUG_LOGGING_ENABLED",
                    mode = resolution.mode,
                    process = hookContext.packageName,
                    reason = debug.reason
                )
            )
        }

        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/metrolist",
                area = "bootstrap",
                event = "HOOK_INITIALIZING",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )

        installSessionHooks()
        installMusicServiceHook()
        onAppLifecycle {
            onTerminate { fetchScope.cancel() }
        }
    }

    private fun installMusicServiceHook() {
        val serviceClass = runCatching {
            MetrolistPlayerConstants.MUSIC_SERVICE_CLASS.toClass()
        }.getOrElse {
            logFailure("hook", "MUSIC_SERVICE_CLASS_FAILED", it)
            return
        }

        runCatching {
            serviceClass
                .method {
                    name = MetrolistPlayerConstants.MUSIC_SERVICE_ON_CREATE
                    paramCount = 0
                }.hook {
                    after {
                        instance?.let { service -> hookedMusicService = service }
                    }
                }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/metrolist",
                    area = "hook",
                    event = "MUSIC_SERVICE_CREATE_HOOK_INSTALLED",
                    reason = MetrolistPlayerConstants.MUSIC_SERVICE_ON_CREATE
                )
            )
        }.onFailure {
            logFailure("hook", "MUSIC_SERVICE_CREATE_HOOK_FAILED", it)
        }

        runCatching {
            serviceClass
                .method {
                    name = MetrolistPlayerConstants.MUSIC_SERVICE_ON_EVENTS
                    paramCount = 2
                }.hook {
                    after {
                        val service = instance ?: return@after
                        hookedMusicService = service
                        onMusicServiceEvents(service, drainPending = true)
                    }
                }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/metrolist",
                    area = "hook",
                    event = "MUSIC_SERVICE_HOOK_INSTALLED",
                    reason = MetrolistPlayerConstants.MUSIC_SERVICE_ON_EVENTS
                )
            )
        }.onFailure { logFailure("hook", "MUSIC_SERVICE_HOOK_FAILED", it) }
    }

    private fun onMusicServiceEvents(service: Any, drainPending: Boolean = true) {
        val track = MetrolistHostMetadata.trackFromService(service) ?: return
        hookedMusicService = service
        generationController.observeTrack(track)
        startLyricsFetchIfNeeded(track, "music-service", drainPending)
    }

    private fun startLyricsFetchIfNeeded(
        track: TrackIdentity,
        source: String,
        drainPending: Boolean = true
    ) {
        val generation = generationPolicy.generation
        if (!generationController.acceptsPublication(track, generation)) return
        if (!MetrolistLyricFetchGate.shouldStartFetch(track, generation, fetchGeneration)) {
            if (drainPending) drainPendingPublication()
            return
        }

        fetchJob?.cancel()
        fetchGeneration = generation
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/metrolist",
                area = "track",
                event = "TRACK_BOUND",
                generation = generation,
                reason = source,
                message = "id=" + track.id.orEmpty() +
                    " title=" + track.title.orEmpty() +
                    " artist=" + track.artist.orEmpty() +
                    " album=" + track.album.orEmpty() +
                    " durationMs=" + track.durationMs
            )
        )
        fetchJob = fetchScope.launch {
            val publication = try {
                MetrolistLyricsFetcher.fetchLyrics(hookContext, track, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logFailure("lyric", "LYRIC_FETCH_FAILED", error)
                null
            }
            mainHandler.post {
                if (!generationPolicy.isGenerationValid(generation)) {
                    logPublicationResult("STALE", generation)
                    return@post
                }
                if (publication == null) {
                    logPublicationResult("NO_LYRIC", generation)
                    return@post
                }
                handlePublication(publication.boundTo(track), allowPending = true)
            }
        }
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                constructor.hook {
                    after {
                        (instanceOrNull as? MediaSession)?.let {
                            sessions.onConstructed(it, MetrolistMediaSessionRegistry.constructorTag(args))
                        }
                    }
                }
            }

            type.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
                .apply { isAccessible = true }
                .hook {
                    before {
                        if (sessions.isModuleWrite()) return@before
                        val session = instanceOrNull as? MediaSession ?: return@before
                        val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                        val platformTrack = MetrolistMediaSessionRegistry.trackFrom(incoming)
                        var outgoing = incoming
                        sessions.onHostMetadata(session, platformTrack, outgoing)
                        hookedMusicService?.let { onMusicServiceEvents(it, drainPending = false) }
                        val hostTrack = generationPolicy.currentTrack
                        val overlayTrack = hostTrack ?: platformTrack ?: return@before
                        attachPendingToHostMetadata(session, outgoing, overlayTrack)?.let {
                            outgoing = it
                        }

                        val snapshot = synchronized(publicationLock) { replaySnapshot }
                        val incomingLyricInfo = outgoing.getString("lyricInfo")
                        val alreadyOwned = MetrolistReplayPolicy.isModuleOwned(
                            incomingLyricInfo,
                            hostPackage
                        )
                        if (!alreadyOwned && MetrolistReplayPolicy.shouldReplay(
                                snapshot,
                                session,
                                platformTrack ?: overlayTrack,
                                hostTrack,
                                generationPolicy.generation,
                                snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                                MetrolistMetadataArtwork.isReadyForLyricInfo(outgoing),
                                incomingLyricInfo
                            )
                        ) {
                            MetrolistNativePublisher.buildReplayMetadata(
                                outgoing,
                                snapshot!!,
                                generationPolicy,
                                hostPackage
                            ).second?.let { outgoing = it }
                        }
                        if (outgoing !== incoming) args[0] = outgoing
                    }
                }

            type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
                .apply { isAccessible = true }
                .hook {
                    before {
                        val session = instanceOrNull as? MediaSession ?: return@before
                        val state = (args.getOrNull(0) as? PlaybackState)?.state
                            ?: PlaybackState.STATE_NONE
                        sessions.onPlaybackState(session, state)
                        drainPendingPublication()
                    }
                }

            type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .hook {
                    before {
                        val session = instanceOrNull as? MediaSession ?: return@before
                        val active = args.getOrNull(0) as? Boolean ?: false
                        sessions.onActive(session, active)
                        drainPendingPublication()
                    }
                }

            type.getDeclaredMethod("release").apply { isAccessible = true }.hook {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    sessions.onReleased(session)
                    synchronized(publicationLock) {
                        if (replaySnapshot?.session?.get() === session) replaySnapshot = null
                    }
                }
            }
        }.onFailure { logFailure("session", "SESSION_HOOK_FAILED", it) }
    }

    private fun handlePublication(publication: MetrolistPublication, allowPending: Boolean) {
        val hinted = publication.trackIdentity()
        val currentTrack = generationPolicy.currentTrack
        val generation = generationPolicy.generation
        val effectiveTrack = if (!hinted.isBlank) hinted else currentTrack
        val session = when {
            effectiveTrack != null && !effectiveTrack.isBlank ->
                sessions.selectUnique(effectiveTrack) as? MediaSession
            else -> sessions.uniqueLiveSession() as? MediaSession
        }
        val metadata = session?.let {
            it.controller.metadata ?: sessions.hostMetadata(it) as? MediaMetadata
        }
        val artworkReady = MetrolistMetadataArtwork.isReadyForLyricInfo(metadata)
        val decision = MetrolistPendingPublicationPolicy.decide(
            publicationTrack = hinted,
            currentHostTrack = currentTrack,
            generationValid = currentTrack != null && generationController.acceptsPublication(
                currentTrack,
                generation
            ),
            uniqueSessionReady = session != null,
            metadataReady = metadata != null,
            artworkReady = artworkReady
        )

        when (decision) {
            MetrolistPendingPublicationPolicy.Decision.PENDING -> {
                if (!allowPending) return
                val replaced = pendingStore.replace(publication)
                val reason = if (metadata != null && !artworkReady) {
                    "PENDING_ARTWORK"
                } else if (replaced == null) {
                    "PENDING_STORED"
                } else {
                    "PENDING_REPLACED"
                }
                logPublicationResult(reason, generation)
            }
            MetrolistPendingPublicationPolicy.Decision.DROP_STALE -> {
                pendingStore.clear()
                logPublicationResult("STALE", generation)
            }
            MetrolistPendingPublicationPolicy.Decision.PUBLISH -> {
                val publishTrack = effectiveTrack ?: return
                val bound = publication.boundTo(publishTrack)
                val publishResult = MetrolistNativePublisher.publish(
                    session = session!!,
                    metadata = metadata!!,
                    publication = bound,
                    track = publishTrack,
                    trackGeneration = generation,
                    generationPolicy = generationPolicy,
                    registry = sessions,
                    hostPackage = hostPackage
                )
                if (publishResult.isPublished) {
                    pendingStore.clear()
                    synchronized(publicationLock) {
                        replaySnapshot = MetrolistReplaySnapshot(
                            WeakReference(session),
                            publishTrack,
                            generation,
                            bound
                        )
                    }
                }
                logPublicationResult(publishResult.name, generation)
            }
        }
    }

    private fun attachPendingToHostMetadata(
        session: MediaSession,
        incoming: MediaMetadata,
        resolvedTrack: TrackIdentity
    ): MediaMetadata? {
        val pending = pendingStore.peek() ?: return null
        if (!MetrolistMetadataArtwork.isReadyForLyricInfo(incoming)) return null
        val generation = generationPolicy.generation
        val currentTrack = generationPolicy.currentTrack ?: resolvedTrack.takeUnless { it.isBlank }
        if (currentTrack == null) return null
        if (!MetrolistLyricDecoder.matchesTrackIdentity(pending.capturedTrack, currentTrack)) {
            return null
        }
        if (!generationController.acceptsPublication(currentTrack, generation)) return null
        val selected = sessions.selectUnique(currentTrack)
        if (selected !== session && selected != null) return null

        val bound = pending.boundTo(currentTrack)
        val snapshot = MetrolistReplaySnapshot(WeakReference(session), currentTrack, generation, bound)
        val (result, patched) = MetrolistNativePublisher.buildReplayMetadata(
            metadata = incoming,
            snapshot = snapshot,
            generationPolicy = generationPolicy,
            hostPackage = hostPackage
        )
        if (!result.isPublished || patched == null) return null
        if (!pendingStore.takeIfSame(pending)) return null

        synchronized(publicationLock) {
            replaySnapshot = snapshot
        }
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/metrolist",
                area = "publisher",
                event = "METROLIST_HOST_METADATA_LYRIC_INFO_ATTACHED",
                generation = generation
            )
        )
        logPublicationResult("PENDING_DRAINED", generation)
        logPublicationResult(result.name, generation)
        return patched
    }

    private fun drainPendingPublication() {
        val pending = pendingStore.peek() ?: return
        val currentTrack = generationPolicy.currentTrack
        if (currentTrack != null &&
            !MetrolistLyricDecoder.matchesTrackIdentity(pending.capturedTrack, currentTrack)
        ) {
            pendingStore.clear()
            logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generationPolicy.generation)
            return
        }
        val session = when {
            currentTrack != null -> sessions.selectUnique(currentTrack) as? MediaSession
            else -> sessions.uniqueLiveSession() as? MediaSession
        } ?: return
        val metadata = session.controller.metadata ?: return
        if (currentTrack == null || !generationController.acceptsPublication(currentTrack)) return
        if (!MetrolistMetadataArtwork.isReadyForLyricInfo(metadata)) return
        handlePublication(pending.boundTo(currentTrack), allowPending = true)
    }

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(
            component = "provider/metrolist",
            area = "publication",
            event = "METROLIST_FINAL_" + result,
            generation = generation
        )
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(
            component = "provider/metrolist",
            area = area,
            event = event,
            reason = error.javaClass.simpleName,
            message = error.message?.take(1000)
        ),
        throwable = error
    )
}
