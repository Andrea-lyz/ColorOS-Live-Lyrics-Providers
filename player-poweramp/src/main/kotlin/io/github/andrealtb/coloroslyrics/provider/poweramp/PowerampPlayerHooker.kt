/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.config.YukiHookDebugSource
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.session.PlaybackStateTranslationToggle
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class PowerampPlayerHooker(
    private val hookContext: Context,
    private val hostPackage: String,
    private val hostVersion: String?
) : YukiBaseHooker() {

    private val sessions = PowerampMediaSessionRegistry()
    private val publicationLock = Any()
    private val pendingStore = PowerampPendingPublicationStore()
    private val trackEventGate = PowerampTrackEventGate()
    private val lyricExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var replaySnapshot: PowerampReplaySnapshot? = null
    private var trackReceiver: BroadcastReceiver? = null

    @Volatile
    private var translationActionInjectionLogged = false

    private val translationPokeGuard = ThreadLocal<Boolean>()
    private val lastTranslationPokedGeneration = AtomicLong(Long.MIN_VALUE)

    private val generationController = PowerampHostGenerationController { _, _ ->
        synchronized(publicationLock) {
            replaySnapshot = null
            pendingStore.clear()
            logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generationPolicy.generation)
        }
    }

    private val generationPolicy: TrackGenerationPolicy
        get() = generationController.policy

    override fun onHook() {
        val resolution = RuntimeModeResolver.resolve(hookContext)
        if (!resolution.mode.isSupported) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = "provider/poweramp",
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
            provider = ProviderId.POWERAMP,
            rootSource = YukiHookDebugSource.create(hookContext)
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/poweramp",
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
                    component = "provider/poweramp",
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
                component = "provider/poweramp",
                area = "bootstrap",
                event = "HOOK_INITIALIZING",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )

        installSessionHooks()
        installTrackChangedSendHook()
        registerTrackReceiver()
        onAppLifecycle {
            onTerminate { release() }
        }
    }

    private fun installTrackChangedSendHook() {
        runCatching {
            ContextWrapper::class.java
                .getDeclaredMethod("sendStickyBroadcast", Intent::class.java)
                .apply { isAccessible = true }
                .hook {
                    before {
                        val intent = args.getOrNull(0) as? Intent ?: return@before
                        if (intent.action == PowerampPlayerConstants.ACTION_TRACK_CHANGED) {
                            onTrackChanged(intent, source = "host-send")
                        }
                    }
                }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/poweramp",
                    area = "hook",
                    event = "TRACK_CHANGED_SEND_HOOK_INSTALLED"
                )
            )
        }.onFailure { logFailure("hook", "TRACK_CHANGED_SEND_HOOK_FAILED", it) }
    }

    private fun registerTrackReceiver() {
        val filter = IntentFilter(PowerampPlayerConstants.ACTION_TRACK_CHANGED)
        trackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerampPlayerConstants.ACTION_TRACK_CHANGED) {
                    onTrackChanged(intent, source = "receiver")
                }
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(
                hookContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/poweramp",
                area = "hook",
                event = "TRACK_CHANGED_RECEIVER_INSTALLED"
            )
        )
    }

    private fun onTrackChanged(intent: Intent, source: String) {
        val snapshot = PowerampTrackIdentity.fromTrackChangedExtras(intent.extras) ?: return
        val eventTimestamp = intent.getLongExtra("ts", Long.MIN_VALUE)
        if (!trackEventGate.shouldHandle(
                eventTimestampMillis = eventTimestamp,
                trackKey = snapshot.track.buildStableKey()
            )
        ) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = "provider/poweramp",
                    area = "track",
                    event = "TRACK_CHANGED_DUPLICATE_SKIPPED",
                    generation = generationPolicy.generation,
                    reason = source
                )
            )
            return
        }
        val generation = generationController.observeTrack(snapshot.track)
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/poweramp",
                area = "track",
                event = "TRACK_BOUND",
                generation = generation,
                trackHash = DiagnosticHasher.sha256(snapshot.track.buildStableKey()),
                reason = "source=$source path=${!snapshot.path.isNullOrBlank()}"
            )
        )
        lyricExecutor.execute {
            val publication = runCatching {
                PowerampLyricLoader.load(hookContext, snapshot)
            }.onFailure {
                logFailure("lyric", "LOCAL_LYRIC_LOAD_FAILED", it)
            }.getOrNull()
            mainHandler.post {
                if (!generationPolicy.isGenerationValid(generation)) {
                    logPublicationResult("STALE", generation)
                    return@post
                }
                if (publication == null) {
                    logPublicationResult("NO_LOCAL_LYRIC", generation)
                    return@post
                }
                handlePublication(publication, allowPending = true)
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
                            sessions.onConstructed(it, PowerampMediaSessionRegistry.constructorTag(args))
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
                        if (sessions.isCastSession(session)) return@before
                        val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                        PowerampArtworkDiagnostics.log(
                            "HOST_IN",
                            incoming,
                            session,
                            generationPolicy.generation
                        )
                        val candidate = PowerampTrackIdentity.fromMetadata(incoming) ?: return@before
                        generationController.observeTrack(candidate)
                        var outgoing = incoming
                        sessions.onHostMetadata(session, candidate, outgoing)
                        attachPendingToHostMetadata(session, outgoing, candidate)?.let {
                            outgoing = it
                        }

                        val snapshot = synchronized(publicationLock) { replaySnapshot }
                        val incomingLyricInfo = outgoing.getString("lyricInfo")
                        val alreadyOwned = PowerampReplayPolicy.isModuleOwned(
                            incomingLyricInfo,
                            hostPackage
                        )
                        if (!alreadyOwned && PowerampReplayPolicy.shouldReplay(
                                snapshot,
                                session,
                                candidate,
                                generationPolicy.currentTrack,
                                generationPolicy.generation,
                                snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                                PowerampMetadataArtwork.isReadyForLyricInfo(outgoing),
                                incomingLyricInfo
                            )
                        ) {
                            PowerampNativePublisher.buildReplayMetadata(
                                outgoing,
                                snapshot!!,
                                generationPolicy,
                                hostPackage
                            ).second?.let { outgoing = it }
                        }
                        if (outgoing !== incoming) args[0] = outgoing
                        PowerampArtworkDiagnostics.log(
                            "HOST_OUT",
                            outgoing,
                            session,
                            generationPolicy.generation
                        )
                    }
                    after {
                        if (sessions.isModuleWrite()) return@after
                        val session = instanceOrNull as? MediaSession ?: return@after
                        if (sessions.isCastSession(session)) return@after
                        val committed = (args.getOrNull(0) as? MediaMetadata)
                            ?: session.controller.metadata
                        pokeTranslationActionIfPlaying(
                            session,
                            !committed?.getString("lyricInfo").isNullOrBlank()
                        )
                    }
                }

            type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
                .apply { isAccessible = true }
                .hook {
                    before {
                        val session = instanceOrNull as? MediaSession ?: return@before
                        if (sessions.isCastSession(session)) return@before
                        val original = args.getOrNull(0) as? PlaybackState
                        // Rewrite host args only. A delayed session.setPlaybackState
                        // races pause and can zero position.
                        if (original != null) {
                            args[0] = injectPublicTranslationToggle(session, original)
                        }
                        val state = (args.getOrNull(0) as? PlaybackState)?.state
                            ?: PlaybackState.STATE_NONE
                        sessions.onPlaybackState(session, state)
                        if (translationPokeGuard.get() != true) {
                            drainPendingPublication()
                        }
                    }
                }

            type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .hook {
                    before {
                        val session = instanceOrNull as? MediaSession ?: return@before
                        if (sessions.isCastSession(session)) return@before
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

    private fun injectPublicTranslationToggle(
        session: MediaSession,
        original: PlaybackState
    ): PlaybackState {
        val generation = generationPolicy.generation
        val metadata = session.controller.metadata ?: sessions.hostMetadata(session) as? MediaMetadata
        val hasLyricInfo = !metadata?.getString("lyricInfo").isNullOrBlank()
        val pokeHostPlaying = translationPokeGuard.get() != true &&
            original.state == PlaybackState.STATE_PLAYING &&
            hasLyricInfo &&
            claimTranslationPoke(generation)
        val pokeToken = if (pokeHostPlaying) SystemClock.elapsedRealtime() else null
        val patched = PlaybackStateTranslationToggle.prependPublicAction(
            original,
            hookContext,
            pokeToken
        )
        if (patched !== original && !translationActionInjectionLogged) {
            translationActionInjectionLogged = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/poweramp",
                    area = "session",
                    event = "TRANSLATION_ACTION_INJECTED",
                    process = hookContext.packageName,
                    reason = "public"
                )
            )
        }
        if (pokeToken != null && patched !== original) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/poweramp",
                    area = "session",
                    event = "TRANSLATION_ACTION_POKED",
                    generation = generation,
                    reason = "host-playing position=${original.position}"
                )
            )
        } else if (pokeToken != null) {
            lastTranslationPokedGeneration.compareAndSet(generation, Long.MIN_VALUE)
        }
        return patched
    }

    private fun pokeTranslationActionIfPlaying(session: MediaSession, hasLyricInfo: Boolean) {
        val generation = generationPolicy.generation
        val live = session.controller.playbackState
        if (!PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = sessions.isCastSession(session),
                isModuleWrite = sessions.isModuleWrite(),
                isPokePass = translationPokeGuard.get() == true,
                liveState = live?.state,
                hasLyricInfo = hasLyricInfo,
                generation = generation,
                lastPokedGeneration = lastTranslationPokedGeneration.get()
            )
        ) {
            return
        }
        val confirm = session.controller.playbackState
        if (confirm == null || confirm.state != PlaybackState.STATE_PLAYING) {
            return
        }
        val patched = PlaybackStateTranslationToggle.prependPublicAction(
            confirm,
            hookContext,
            SystemClock.elapsedRealtime()
        )
        val stillPlaying = session.controller.playbackState
        if (stillPlaying == null || stillPlaying.state != PlaybackState.STATE_PLAYING) {
            return
        }
        if (!generationPolicy.isGenerationValid(generation)) return
        if (!claimTranslationPoke(generation)) return
        translationPokeGuard.set(true)
        var committed = false
        try {
            session.setPlaybackState(patched)
            committed = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/poweramp",
                    area = "session",
                    event = "TRANSLATION_ACTION_POKED",
                    generation = generation,
                    reason = "playing-lyricInfo position=${confirm.position}"
                )
            )
        } finally {
            if (!committed) {
                lastTranslationPokedGeneration.compareAndSet(generation, Long.MIN_VALUE)
            }
            translationPokeGuard.remove()
        }
    }

    private fun claimTranslationPoke(generation: Long): Boolean {
        while (true) {
            val previous = lastTranslationPokedGeneration.get()
            if (previous >= generation) return false
            if (lastTranslationPokedGeneration.compareAndSet(previous, generation)) return true
        }
    }

    private fun handlePublication(publication: PowerampPublication, allowPending: Boolean) {
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
        val artworkReady = PowerampMetadataArtwork.isReadyForLyricInfo(metadata)
        val decision = PowerampPendingPublicationPolicy.decide(
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
            PowerampPendingPublicationPolicy.Decision.PENDING -> {
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
            PowerampPendingPublicationPolicy.Decision.DROP_STALE -> {
                pendingStore.clear()
                logPublicationResult("STALE", generation)
            }
            PowerampPendingPublicationPolicy.Decision.PUBLISH -> {
                val publishTrack = effectiveTrack ?: return
                val bound = publication.boundTo(publishTrack)
                val publishResult = PowerampNativePublisher.publish(
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
                        replaySnapshot = PowerampReplaySnapshot(
                            WeakReference(session),
                            publishTrack,
                            generation,
                            bound
                        )
                    }
                    pokeTranslationActionIfPlaying(session, hasLyricInfo = true)
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
        if (!PowerampMetadataArtwork.isReadyForLyricInfo(incoming)) return null
        val generation = generationPolicy.generation
        val currentTrack = generationPolicy.currentTrack ?: resolvedTrack.takeUnless { it.isBlank }
        if (currentTrack == null) return null
        if (!PowerampLyricDecoder.matchesTrackIdentity(pending.capturedTrack, currentTrack)) {
            return null
        }
        if (!generationController.acceptsPublication(currentTrack, generation)) return null
        val selected = sessions.selectUnique(currentTrack)
        if (selected !== session && selected != null) return null

        val bound = pending.boundTo(currentTrack)
        val snapshot = PowerampReplaySnapshot(WeakReference(session), currentTrack, generation, bound)
        val (result, patched) = PowerampNativePublisher.buildReplayMetadata(
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
                component = "provider/poweramp",
                area = "publisher",
                event = "POWERAMP_HOST_METADATA_LYRIC_INFO_ATTACHED",
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
            !PowerampLyricDecoder.matchesTrackIdentity(pending.capturedTrack, currentTrack)
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
        if (!PowerampMetadataArtwork.isReadyForLyricInfo(metadata)) return
        handlePublication(pending.boundTo(currentTrack), allowPending = true)
    }

    private fun release() {
        trackReceiver?.let { receiver ->
            runCatching { hookContext.unregisterReceiver(receiver) }
        }
        trackReceiver = null
        lyricExecutor.shutdownNow()
    }

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(
            component = "provider/poweramp",
            area = "publication",
            event = "POWERAMP_FINAL_$result",
            generation = generation
        )
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(
            component = "provider/poweramp",
            area = area,
            event = event,
            reason = error.javaClass.simpleName,
            message = error.message?.take(1000)
        ),
        throwable = error
    )
}
