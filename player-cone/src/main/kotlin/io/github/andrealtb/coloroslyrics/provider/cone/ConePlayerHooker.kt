/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.session.PlaybackStateTranslationToggle
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookContext
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionCache
import java.lang.ref.WeakReference

class ConePlayerHooker(private val hookContext: ProviderHookContext) {
    private val hookRuntime = hookContext.runtime
    private val hostContext = hookContext.application
    private val hostPackage = hookContext.packageName
    private val hostVersion = hookContext.hostVersion

    private val sessions = ConeMediaSessionRegistry()
    private val candidatePolicy = ConeLyricCandidatePolicy()
    private val publicationLock = Any()
    private val reflectionCache = ReflectionCache(hookContext.classLoader, hostVersion)
    private val pendingStore = ConePendingPublicationStore()
    private var replaySnapshot: ConeReplaySnapshot? = null

    private val generationController = ConeHostGenerationController(sessions) {
        synchronized(publicationLock) {
            candidatePolicy.clear()
            replaySnapshot = null
        }
    }

    private val generationPolicy: TrackGenerationPolicy
        get() = generationController.policy

    @Volatile
    private var broadcastReceiverRegistered = false

    @Volatile
    private var translationActionInjectionLogged = false

    fun onHook() {
        val resolution = RuntimeModeResolver.resolve(hostContext)
        if (!resolution.mode.isSupported) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = "provider/cone",
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
            provider = ProviderId.CONE,
            rootSource = hookContext.debugSource,
            frameworkSink = hookContext.frameworkSink
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/cone",
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
                    component = "provider/cone",
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
                component = "provider/cone",
                area = "bootstrap",
                event = "HOOK_INITIALIZING",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )

        installSessionHooks()
        installBroadcastReceiver()
        installMediaPlayerServiceHooks()
    }

    private fun installBroadcastReceiver() {
        if (broadcastReceiverRegistered) return
        synchronized(publicationLock) {
            if (broadcastReceiverRegistered) return
            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        onBroadcastReceived(intent)
                    }
                }
                val filter = IntentFilter(ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hostContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    hostContext.registerReceiver(receiver, filter)
                }
                broadcastReceiverRegistered = true
                StructuredDiagnostics.logInfo(
                    DiagnosticEvent(
                        component = "provider/cone",
                        area = "broadcast",
                        event = "BROADCAST_RECEIVER_REGISTERED",
                        reason = ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED
                    )
                )
            }.onFailure {
                logFailure("broadcast", "BROADCAST_RECEIVER_REGISTER_FAILED", it)
            }
        }
    }

    private fun onBroadcastReceived(intent: Intent?) {
        val rawLyric = ConeBroadcastLyricExtractor.extract(intent) ?: return
        val currentTrack = generationPolicy.currentTrack ?: sessions.uniqueCurrentTrack()
        val candidate = candidatePolicy.evaluate(
            source = ConeLyricSource.BROADCAST,
            rawLyric = rawLyric,
            trackHint = currentTrack
        ) ?: return

        val publication = ConePublication(
            source = candidate.source,
            rawLyric = candidate.rawLyric,
            lines = candidate.lines,
            trackHint = candidate.trackHint
        )
        handlePublication(publication, allowPending = true)
    }

    private fun installMediaPlayerServiceHooks() {
        runCatching {
            val serviceClass = reflectionCache.getOrPutClass(ConePlayerConstants.MEDIA_PLAYER_SERVICE_CLASS) {
                hookContext.classLoader.loadClass(ConePlayerConstants.MEDIA_PLAYER_SERVICE_CLASS)
            }

            for (method in serviceClass.declaredMethods) {
                val paramTypes = method.parameterTypes
                if (method.name == "onTracksChanged" && paramTypes.size == 2 &&
                    paramTypes[1].name == ConePlayerConstants.MEDIA3_TRACKS_CLASS
                ) {
                    hookRuntime.hook(method, "cone.service.MediaPlayerService#${method.name}") {
                        after {
                            val tracks = args.getOrNull(1) ?: return@after
                            onTracksChanged(tracks)
                        }
                    }
                    StructuredDiagnostics.logInfo(
                        DiagnosticEvent(
                            component = "provider/cone",
                            area = "hook",
                            event = "ON_TRACKS_CHANGED_HOOK_INSTALLED"
                        )
                    )
                    break
                }
            }
        }.onFailure {
            logFailure("hook", "MEDIA_PLAYER_SERVICE_HOOK_FAILED", it)
        }
    }

    private fun onTracksChanged(tracks: Any) {
        val rawLyric = ConeTrackMetadataExtractor.findSelectedAudioLyric(tracks) ?: return
        val currentTrack = generationPolicy.currentTrack ?: sessions.uniqueCurrentTrack()
        val candidate = candidatePolicy.evaluate(
            source = ConeLyricSource.TRACK_METADATA,
            rawLyric = rawLyric,
            trackHint = currentTrack
        ) ?: return

        val publication = ConePublication(
            source = candidate.source,
            rawLyric = candidate.rawLyric,
            lines = candidate.lines,
            trackHint = candidate.trackHint
        )
        handlePublication(publication, allowPending = true)
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEachIndexed { index, constructor ->
                hookRuntime.hook(constructor, "cone.session.MediaSession#ctor$index") {
                    after {
                        (instanceOrNull as? MediaSession)?.let {
                            sessions.onConstructed(it, ConeMediaSessionRegistry.constructorTag(args))
                        }
                    }
                }
            }

            hookRuntime.hook(
                type.getDeclaredMethod("setMetadata", MediaMetadata::class.java),
                "cone.session.MediaSession#setMetadata"
            ) {
                before {
                    if (sessions.isModuleWrite()) return@before
                    val session = instanceOrNull as? MediaSession ?: return@before
                    val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                    val track = ConeMediaSessionRegistry.trackFrom(incoming) ?: return@before

                    sessions.onHostMetadata(session, track, incoming)
                    observeGenerationFromHostMainSession()
                    attachPendingToHostMetadata(session, incoming)?.let { args[0] = it }

                    val snapshot = synchronized(publicationLock) { replaySnapshot }
                    val selected = sessions.selectUnique(track)
                    val replayBase = args.getOrNull(0) as? MediaMetadata ?: incoming
                    val incomingLyricInfo = replayBase.getString("lyricInfo")
                    val alreadyOwned = ConeReplayPolicy.isModuleOwned(incomingLyricInfo)

                    if (!alreadyOwned && ConeReplayPolicy.shouldReplay(
                            snapshot, selected, track, generationPolicy.currentTrack,
                            generationPolicy.generation,
                            snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                            incomingLyricInfo
                        )
                    ) {
                        ConeNativePublisher.buildReplayMetadata(replayBase, snapshot!!, generationPolicy, hostPackage)
                            .second?.let { args[0] = it }
                    }
                }
            }

            hookRuntime.hook(
                type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java),
                "cone.session.MediaSession#setPlaybackState"
            ) {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    val original = args.getOrNull(0) as? PlaybackState
                    if (original != null) args[0] = injectPublicTranslationToggle(original)
                    val state = (args.getOrNull(0) as? PlaybackState)?.state ?: PlaybackState.STATE_NONE
                    sessions.onPlaybackState(session, state)
                    observeGenerationFromHostMainSession()
                    drainPendingPublication()
                }
            }

            hookRuntime.hook(
                type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType),
                "cone.session.MediaSession#setActive"
            ) {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    val active = args.getOrNull(0) as? Boolean ?: false
                    sessions.onActive(session, active)
                    observeGenerationFromHostMainSession()
                    drainPendingPublication()
                }
            }

            hookRuntime.hook(type.getDeclaredMethod("release"), "cone.session.MediaSession#release") {
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

    private fun injectPublicTranslationToggle(original: PlaybackState): PlaybackState {
        val patched = PlaybackStateTranslationToggle.prependPublicAction(original, hostContext)
        if (patched !== original && !translationActionInjectionLogged) {
            translationActionInjectionLogged = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/cone",
                    area = "session",
                    event = "TRANSLATION_ACTION_INJECTED",
                    process = hookContext.packageName,
                    reason = "public"
                )
            )
        }
        return patched
    }

    private fun observeGenerationFromHostMainSession() {
        generationController.observeUniqueHostMainTrack()
    }

    private fun handlePublication(publication: ConePublication, allowPending: Boolean) {
        val track = publication.trackIdentity()
        val currentTrack = generationPolicy.currentTrack
        val generation = generationPolicy.generation
        val session = sessions.selectUnique(track) as? MediaSession
        val metadata = session?.let { sessions.hostMetadata(it) as? MediaMetadata }
        val decision = ConePendingPublicationPolicy.decide(
            publicationTrack = track,
            currentHostTrack = currentTrack,
            generationValid = generationController.acceptsPublication(track, generation),
            uniqueSessionReady = session != null,
            metadataReady = metadata != null
        )

        when (decision) {
            ConePendingPublicationPolicy.Decision.PENDING -> {
                if (!allowPending) return
                val replaced = pendingStore.replace(publication)
                logPublicationResult(if (replaced == null) "PENDING_STORED" else "PENDING_REPLACED", generation)
            }
            ConePendingPublicationPolicy.Decision.DROP_STALE -> {
                val pending = pendingStore.peek()
                if (pending != null && currentTrack != null &&
                    !TrackIdentityPolicy.isSameTrack(pending.trackIdentity(), currentTrack)
                ) {
                    pendingStore.clear()
                    logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generation)
                }
                logPublicationResult("STALE", generation)
            }
            ConePendingPublicationPolicy.Decision.PUBLISH -> {
                val publishResult = ConeNativePublisher.publish(
                    session = session!!,
                    metadata = metadata!!,
                    publication = publication,
                    trackGeneration = generation,
                    generationPolicy = generationPolicy,
                    registry = sessions,
                    hostPackage = hostPackage
                )
                if (publishResult.isPublished) {
                    synchronized(publicationLock) {
                        replaySnapshot = ConeReplaySnapshot(WeakReference(session), track, generation, publication)
                    }
                }
                logPublicationResult(publishResult.name, generation)
            }
        }
    }

    private fun attachPendingToHostMetadata(session: MediaSession, incoming: MediaMetadata): MediaMetadata? {
        val pending = pendingStore.peek() ?: return null
        val track = pending.trackIdentity()
        val generation = generationPolicy.generation
        if (!generationController.acceptsPublication(track, generation)) return null
        if (sessions.selectUnique(track) !== session) return null

        val snapshot = ConeReplaySnapshot(WeakReference(session), track, generation, pending)
        val (result, patched) = ConeNativePublisher.buildReplayMetadata(
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
                component = "provider/cone",
                area = "publisher",
                event = "CONE_HOST_METADATA_LYRIC_INFO_ATTACHED",
                generation = generation
            )
        )
        logPublicationResult("PENDING_DRAINED", generation)
        logPublicationResult(result.name, generation)
        return patched
    }

    private fun drainPendingPublication() {
        val pending = pendingStore.peek() ?: return
        val track = pending.trackIdentity()
        val currentTrack = generationPolicy.currentTrack
        if (currentTrack != null && !TrackIdentityPolicy.isSameTrack(track, currentTrack)) {
            pendingStore.clear()
            logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generationPolicy.generation)
            return
        }
        val session = sessions.selectUnique(track) as? MediaSession ?: return
        val metadata = sessions.hostMetadata(session) as? MediaMetadata ?: return
        if (!generationController.acceptsPublication(track)) return
        pendingStore.take()
        logPublicationResult("PENDING_DRAINED", generationPolicy.generation)
        handlePublication(pending, allowPending = false)
    }

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(
            component = "provider/cone",
            area = "publication",
            event = "CONE_FINAL_$result",
            generation = generation
        )
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(
            component = "provider/cone",
            area = area,
            event = event,
            reason = error.javaClass.simpleName,
            message = error.message?.take(1000)
        ),
        throwable = error
    )
}
