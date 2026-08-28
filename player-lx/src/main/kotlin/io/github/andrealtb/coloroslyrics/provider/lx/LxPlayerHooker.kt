/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.config.YukiHookDebugSource
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.session.PlaybackStateTranslationToggle
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionCache
import java.lang.ref.WeakReference

class LxPlayerHooker(
    private val hookContext: Context,
    private val hostPackage: String,
    private val hostVersion: String?
) : YukiBaseHooker() {

    private val sessions = LxMediaSessionRegistry()
    private val publicationLock = Any()
    private val reflectionCache = ReflectionCache(hookContext.classLoader, hostVersion)
    private val pendingStore = LxPendingPublicationStore()
    private var replaySnapshot: LxReplaySnapshot? = null

    @Volatile
    private var bluetoothProjectionLogged = false

    @Volatile
    private var translationActionInjectionLogged = false

    private val generationController = LxHostGenerationController { _, current ->
        synchronized(publicationLock) {
            replaySnapshot = null
            val pending = pendingStore.peek()
            if (pending != null &&
                LxLyricModuleResolver.shouldDropPendingOnTrackChange(pending.capturedTrack, current)
            ) {
                pendingStore.clear()
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
                    component = "provider/lx",
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
            provider = ProviderId.LX,
            rootSource = YukiHookDebugSource.create(hookContext)
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/lx",
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
                    component = "provider/lx",
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
                component = "provider/lx",
                area = "bootstrap",
                event = "HOOK_INITIALIZING",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )

        installSessionHooks()
        installLyricModuleHook()
    }

    private fun installLyricModuleHook() {
        runCatching {
            reflectionCache.ensureValid(hookContext.classLoader, hostVersion)
            val lyricModule = reflectionCache.getOrPutClass("lx.lyricModule") {
                LxLyricModuleResolver.findLyricModuleClass(
                    hookContext.classLoader,
                    hostPackage,
                    hostVersion
                )
            }
            val setLyric = reflectionCache.getOrPutMethod("lx.setLyric") {
                LxLyricModuleResolver.findSetLyricMethod(lyricModule, hostVersion)
            }.apply { isAccessible = true }
            setLyric.hook {
                after {
                    onSetLyric(
                        args.getOrNull(0) as? String,
                        args.getOrNull(1) as? String
                    )
                }
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/lx",
                    area = "hook",
                    event = "LYRIC_MODULE_HOOK_INSTALLED",
                    reason = "${lyricModule.name}#${setLyric.name}"
                )
            )
        }.onFailure {
            logFailure("hook", "LYRIC_MODULE_HOOK_FAILED", it)
        }
    }

    private fun onSetLyric(lyric: String?, translation: String?) {
        if (!LxLyricDecoder.containsTimedLrc(lyric)) {
            pendingStore.clear()
            // Keep replaySnapshot. musicToggled → handleSetLyric('') and both Bluetooth
            // title switches then call TrackPlayer.updateNowPlayingTitles without lyricInfo.
            // Same-track replay must reattach lyrics; a real track change still drops the
            // snapshot from LxHostGenerationController.onTrackChanged.
            logPublicationResult("EMPTY_CLEARED", generationPolicy.generation)
            return
        }
        val publication = LxLyricDecoder.decode(
            lyric = lyric,
            translation = translation,
            capturedTrack = generationPolicy.currentTrack
        )
        if (publication == null) {
            logPublicationResult("UNTIMED", generationPolicy.generation)
            return
        }
        handlePublication(publication, allowPending = true)
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                constructor.hook {
                    after {
                        (instanceOrNull as? MediaSession)?.let {
                            sessions.onConstructed(it, LxMediaSessionRegistry.constructorTag(args))
                        }
                    }
                }
            }

            type.getDeclaredMethod("setMetadata", MediaMetadata::class.java).apply { isAccessible = true }.hook {
                before {
                    if (sessions.isModuleWrite()) return@before
                    val session = instanceOrNull as? MediaSession ?: return@before
                    val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                    LxArtworkDiagnostics.log(
                        "HOST_IN",
                        incoming,
                        session,
                        generationPolicy.generation
                    )
                    val candidate = LxMediaSessionRegistry.trackFrom(incoming)
                    val stable = generationPolicy.currentTrack ?: sessions.uniqueCurrentTrack()
                    val resolved = LxBluetoothLyricMetadataPolicy.resolve(stable, candidate)
                        ?: return@before
                    generationController.observeTrack(resolved.track.copy(id = null))
                    if (resolved.projection) {
                        if (!bluetoothProjectionLogged) {
                            bluetoothProjectionLogged = true
                            StructuredDiagnostics.logInfo(
                                DiagnosticEvent(
                                    component = "provider/lx",
                                    area = "relay",
                                    event = "LX_BLUETOOTH_PROJECTION_IGNORED",
                                    reason = "title-artist"
                                )
                            )
                        }
                    } else {
                        bluetoothProjectionLogged = false
                    }

                    var outgoing = LxMetadataArtwork.prepareIdentityForSystemUi(incoming, resolved.track)
                    if (outgoing !== incoming) {
                        LxArtworkDiagnostics.log(
                            "HOST_IDENTITY",
                            outgoing,
                            session,
                            generationPolicy.generation
                        )
                    }
                    sessions.onHostMetadata(session, resolved.track, outgoing)
                    attachPendingToHostMetadata(session, outgoing, resolved.track)?.let { outgoing = it }

                    val snapshot = synchronized(publicationLock) { replaySnapshot }
                    val incomingLyricInfo = outgoing.getString("lyricInfo")
                    val alreadyOwned = LxReplayPolicy.isModuleOwned(incomingLyricInfo, hostPackage)

                    if (!alreadyOwned && LxReplayPolicy.shouldReplay(
                            snapshot, session, resolved.track, generationPolicy.currentTrack,
                            generationPolicy.generation,
                            snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                            LxMetadataArtwork.isReadyForLyricInfo(outgoing),
                            incomingLyricInfo
                        )
                    ) {
                        LxNativePublisher.buildReplayMetadata(
                            outgoing,
                            snapshot!!,
                            generationPolicy,
                            hostPackage
                        ).second?.let { outgoing = it }
                    }
                    if (outgoing !== incoming) args[0] = outgoing
                    LxArtworkDiagnostics.log(
                        "HOST_OUT",
                        outgoing,
                        session,
                        generationPolicy.generation
                    )
                }
            }

            type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java).apply { isAccessible = true }.hook {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    val original = args.getOrNull(0) as? PlaybackState
                    if (original != null) args[0] = injectPublicTranslationToggle(original)
                    val state = (args.getOrNull(0) as? PlaybackState)?.state ?: PlaybackState.STATE_NONE
                    sessions.onPlaybackState(session, state)
                    drainPendingPublication()
                }
            }

            type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.hook {
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

    private fun injectPublicTranslationToggle(original: PlaybackState): PlaybackState {
        val patched = PlaybackStateTranslationToggle.prependPublicAction(original, hookContext)
        if (patched !== original && !translationActionInjectionLogged) {
            translationActionInjectionLogged = true
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = "provider/lx",
                    area = "session",
                    event = "TRANSLATION_ACTION_INJECTED",
                    process = hookContext.packageName,
                    reason = "public"
                )
            )
        }
        return patched
    }

    private fun handlePublication(publication: LxPublication, allowPending: Boolean) {
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
        val artworkReady = LxMetadataArtwork.isReadyForLyricInfo(metadata)
        val decision = LxPendingPublicationPolicy.decide(
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
            LxPendingPublicationPolicy.Decision.PENDING -> {
                if (!allowPending) return
                val replaced = pendingStore.replace(publication)
                val reason = if (metadata != null && !artworkReady) "PENDING_ARTWORK" else if (
                    replaced == null
                ) "PENDING_STORED" else "PENDING_REPLACED"
                logPublicationResult(reason, generation)
            }
            LxPendingPublicationPolicy.Decision.DROP_STALE -> {
                val pending = pendingStore.peek()
                if (pending != null && currentTrack != null &&
                    LxLyricModuleResolver.shouldDropPendingOnTrackChange(pending.capturedTrack, currentTrack)
                ) {
                    pendingStore.clear()
                    logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generation)
                }
                logPublicationResult("STALE", generation)
            }
            LxPendingPublicationPolicy.Decision.PUBLISH -> {
                val publishTrack = effectiveTrack ?: return
                val bound = publication.boundTo(publishTrack)
                val publishResult = LxNativePublisher.publish(
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
                    synchronized(publicationLock) {
                        replaySnapshot = LxReplaySnapshot(
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
        if (!LxMetadataArtwork.isReadyForLyricInfo(incoming)) return null
        val generation = generationPolicy.generation
        val currentTrack = generationPolicy.currentTrack ?: resolvedTrack.takeUnless { it.isBlank }
        if (currentTrack == null) return null
        if (!LxLyricDecoder.matchesTrackIdentity(pending.capturedTrack, currentTrack)) return null
        if (!generationController.acceptsPublication(currentTrack, generation)) return null
        val selected = sessions.selectUnique(currentTrack)
        if (selected !== session && selected != null) return null

        val bound = pending.boundTo(currentTrack)
        val snapshot = LxReplaySnapshot(WeakReference(session), currentTrack, generation, bound)
        val (result, patched) = LxNativePublisher.buildReplayMetadata(
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
                component = "provider/lx",
                area = "publisher",
                event = "LX_HOST_METADATA_LYRIC_INFO_ATTACHED",
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
            LxLyricModuleResolver.shouldDropPendingOnTrackChange(pending.capturedTrack, currentTrack)
        ) {
            pendingStore.clear()
            logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generationPolicy.generation)
            return
        }
        val session = when {
            currentTrack != null -> sessions.selectUnique(currentTrack) as? MediaSession
            else -> sessions.uniqueLiveSession() as? MediaSession
        } ?: return
        val metadata = sessions.hostMetadata(session) as? MediaMetadata ?: return
        if (currentTrack == null || !generationController.acceptsPublication(currentTrack)) return
        if (!LxMetadataArtwork.isReadyForLyricInfo(metadata)) return
        if (!pendingStore.takeIfSame(pending)) return
        logPublicationResult("PENDING_DRAINED", generationPolicy.generation)
        handlePublication(pending.boundTo(currentTrack), allowPending = false)
    }

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(
            component = "provider/lx",
            area = "publication",
            event = "LX_FINAL_$result",
            generation = generation
        )
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(
            component = "provider/lx",
            area = area,
            event = event,
            reason = error.javaClass.simpleName,
            message = error.message?.take(1000)
        ),
        throwable = error
    )
}
