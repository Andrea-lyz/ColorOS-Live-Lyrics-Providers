/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class SpotifyPlayerHooker(
    providerContext: ProviderHookContext
) {

    private val hookContext: Context = providerContext.application
    private val hostPackage: String = providerContext.packageName
    private val hostVersion: String? = providerContext.hostVersion
    private val hookRuntime = providerContext.runtime
    private val appClassLoader: ClassLoader = providerContext.classLoader
    private val debugSource = providerContext.debugSource
    private val frameworkSink = providerContext.frameworkSink

    private val sessions = SpotifyMediaSessionRegistry()
    private val publicationLock = Any()
    private val pendingStore = SpotifyPendingPublicationStore()
    private val authHeaders = SpotifyAuthHeaderStore()
    private val fetcher = SpotifyLyricFetcher()
    private val fetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var replaySnapshot: SpotifyReplaySnapshot? = null

    @Volatile
    private var fetchGeneration: Long? = null

    @Volatile
    private var fetchJob: Job? = null

    private val generationController = SpotifyHostGenerationController { previous, current ->
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
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = SpotifyPlayerConstants.COMPONENT,
                area = "track",
                event = "TRACK_GENERATION_RESET",
                generation = generationPolicy.generation,
                trackHash = DiagnosticHasher.sha256(current.buildStableKey()),
                reason = "identity-changed",
                message = "previousPresent=${previous != null}"
            )
        )
    }

    private val generationPolicy: TrackGenerationPolicy
        get() = generationController.policy

    fun onHook() {
        val resolution = RuntimeModeResolver.resolve(hookContext)
        if (!resolution.mode.isSupported) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
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
            provider = ProviderId.SPOTIFY,
            rootSource = debugSource,
            frameworkSink = frameworkSink
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = SpotifyPlayerConstants.COMPONENT,
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
                    component = SpotifyPlayerConstants.COMPONENT,
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
                component = SpotifyPlayerConstants.COMPONENT,
                area = "bootstrap",
                event = "HOOK_INITIALIZING",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )

        installSessionHooks()
        installHeaderHooks()
        installApplicationHooks()

        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = SpotifyPlayerConstants.COMPONENT,
                area = "bootstrap",
                event = "PROCESS_READY",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )
    }

    private fun installApplicationHooks() {
        runCatching {
            val applicationClass = hookContext.applicationContext.javaClass
            val method = applicationClass.methods.firstOrNull {
                it.name == "onTerminate" && it.parameterCount == 0
            } ?: Application::class.java.getDeclaredMethod("onTerminate")
            method.isAccessible = true
            hookRuntime.hook(
                method,
                "spotify.app.${method.declaringClass.name}#onTerminate"
            ) {
                after { fetchScope.cancel() }
            }
        }.onFailure { logFailure("hook", "APPLICATION_TERMINATE_HOOK_FAILED", it) }
    }

    private fun installHeaderHooks() {
        var installed = 0
        installed += installShadedHeaderHooks()
        installed += installCronetHeaderHooks()
        installed += installOkHttpHeaderHooks()
        if (installed == 0) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "AUTH_HEADER_HOOK_UNAVAILABLE",
                    reason = "shaded-cronet-and-okhttp-missing"
                )
            )
        }
    }

    private fun hostApkPaths(): List<String> {
        val applicationInfo = hookContext.applicationInfo
        return buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.let(::addAll)
        }
    }

    private fun installShadedHeaderHooks(): Int = runCatching {
        val constructors = SpotifyShadedHeadersResolver.resolve(
            hostApkPaths(),
            appClassLoader
        )
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            hookRuntime.hook(
                constructor,
                "spotify.headers.shaded.${constructor.declaringClass.name}#constructor$index"
            ) {
                after {
                    captureHeaderBlock(args.getOrNull(0), "spotify-shaded-okhttp")
                }
            }
        }
        if (constructors.isNotEmpty()) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "SHADED_HEADERS_HOOK_INSTALLED",
                    reason = constructors.joinToString { constructor ->
                        constructor.declaringClass.name + "#<init>"
                    }
                )
            )
        }
        constructors.size
    }.onFailure {
        logFailure("hook", "SHADED_HEADERS_HOOK_FAILED", it)
    }.getOrDefault(0)

    private fun installCronetHeaderHooks(): Int = runCatching {
        val methods = SpotifyCronetHeaderResolver.resolve(
            hostApkPaths(),
            appClassLoader
        )
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            hookRuntime.hook(
                method,
                "spotify.headers.cronet.${method.declaringClass.name}#${method.name}$index"
            ) {
                before {
                    captureHeaderPair(
                        args.getOrNull(0) as? String,
                        args.getOrNull(1) as? String,
                        "cronet"
                    )
                }
            }
        }
        if (methods.isNotEmpty()) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "CRONET_HEADER_HOOK_INSTALLED",
                    reason = methods.joinToString { method ->
                        method.declaringClass.name + "#" + method.name
                    }
                )
            )
        }
        methods.size
    }.onFailure {
        logFailure("hook", "CRONET_HEADER_HOOK_FAILED", it)
    }.getOrDefault(0)

    private fun installOkHttpHeaderHooks(): Int = runCatching {
            val headersClass = appClassLoader.loadClass("okhttp3.Headers")
            headersClass.declaredConstructors.forEachIndexed { index, constructor ->
                constructor.isAccessible = true
                hookRuntime.hook(
                    constructor,
                    "spotify.headers.okhttp.Headers#constructor$index"
                ) {
                    after {
                        captureHeaderBlock(args.getOrNull(0), "okhttp")
                    }
                }
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "OKHTTP_HEADERS_HOOK_INSTALLED",
                    reason = "okhttp3.Headers"
                )
            )
            headersClass.declaredConstructors.size
        }.onFailure {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "OKHTTP_HEADERS_HOOK_UNAVAILABLE",
                    reason = it.javaClass.simpleName
                )
            )
        }.getOrDefault(0)

    private fun captureHeaderBlock(raw: Any?, source: String) {
        val beforeKeys = authHeaders.capturedKeyList()
        val becameReady = authHeaders.ingest(raw)
        onHeadersCaptured(beforeKeys, becameReady, source)
    }

    private fun captureHeaderPair(name: String?, value: String?, source: String) {
        val beforeKeys = authHeaders.capturedKeyList()
        val becameReady = authHeaders.ingest(name, value)
        onHeadersCaptured(beforeKeys, becameReady, source)
    }

    private fun onHeadersCaptured(beforeKeys: String, becameReady: Boolean, source: String) {
        val afterKeys = authHeaders.capturedKeyList()
        if (beforeKeys != afterKeys || becameReady) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "auth",
                    event = if (becameReady) {
                        "AUTH_HEADERS_READY"
                    } else {
                        "AUTH_HEADERS_PARTIAL"
                    },
                    generation = generationPolicy.generation,
                    reason = "source=$source keys=$afterKeys",
                    message = "before=$beforeKeys ready=" + authHeaders.hasRequired()
                )
            )
        }
        if (becameReady) {
            mainHandler.post { retryFetchAfterHeadersReady() }
        }
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEachIndexed { index, constructor ->
                constructor.isAccessible = true
                hookRuntime.hook(constructor, "spotify.session.MediaSession#constructor$index") {
                    after {
                        (instanceOrNull as? MediaSession)?.let {
                            sessions.onConstructed(it, SpotifyMediaSessionRegistry.constructorTag(args))
                        }
                    }
                }
            }

            type.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
                .apply { isAccessible = true }
                .also { method ->
                    hookRuntime.hook(method, "spotify.session.MediaSession#setMetadata") {
                    before {
                        if (sessions.isModuleWrite()) return@before
                        val session = instanceOrNull as? MediaSession ?: return@before
                        if (sessions.isCastSession(session)) return@before
                        val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                        var outgoing = incoming
                        val ignored = SpotifyTrackBindPolicy.shouldIgnoreMetadata(incoming)
                        val platformTrack = SpotifyTrackBindPolicy.fromMetadata(incoming)
                        val diagnosticTrack = platformTrack ?: TrackIdentity(
                            id = incoming.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                            title = incoming.getString(MediaMetadata.METADATA_KEY_TITLE),
                            artist = incoming.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        )
                        StructuredDiagnostics.logInfo(
                            DiagnosticEvent(
                                component = SpotifyPlayerConstants.COMPONENT,
                                area = "session",
                                event = "SESSION_METADATA_OBSERVED",
                                generation = generationPolicy.generation,
                                trackHash = DiagnosticHasher.sha256(diagnosticTrack.buildStableKey()),
                                reason = if (ignored) "ignored" else "track",
                                message = "parsed=${platformTrack != null}" +
                                    " moduleLyricInfo=" + !incoming.getString(
                                        SpotifyPlayerConstants.METADATA_KEY_LYRIC_INFO
                                    ).isNullOrEmpty() +
                                    " fetchGeneration=" + fetchGeneration
                            )
                        )
                        sessions.onHostMetadata(session, platformTrack, outgoing)
                        if (ignored) {
                            if (SpotifyReplayPolicy.shouldStripStale(
                                    outgoing.getString(SpotifyPlayerConstants.METADATA_KEY_LYRIC_INFO),
                                    hostPackage,
                                    platformTrack,
                                    generationPolicy.currentTrack
                                )
                            ) {
                                outgoing = SpotifyMetadataArtwork.stripLyricInfo(outgoing)
                            }
                            if (outgoing !== incoming) args[0] = outgoing
                            return@before
                        }
                        platformTrack?.let { observeAuthoritativeTrack(it, "metadata") }
                        platformTrack?.let { startLyricsFetchIfNeeded(it, "metadata") }
                        val hostTrack = generationPolicy.currentTrack
                        val overlayTrack = hostTrack ?: platformTrack ?: return@before
                        attachPendingToHostMetadata(session, outgoing, overlayTrack)?.let {
                            outgoing = it
                        }

                        val snapshot = synchronized(publicationLock) { replaySnapshot }
                        val incomingLyricInfo = outgoing.getString(
                            SpotifyPlayerConstants.METADATA_KEY_LYRIC_INFO
                        )
                        if (SpotifyReplayPolicy.shouldStripStale(
                                incomingLyricInfo,
                                hostPackage,
                                platformTrack,
                                hostTrack
                            )
                        ) {
                            outgoing = SpotifyMetadataArtwork.stripLyricInfo(outgoing)
                        }
                        val alreadyOwned = SpotifyReplayPolicy.isModuleOwned(
                            outgoing.getString(SpotifyPlayerConstants.METADATA_KEY_LYRIC_INFO),
                            hostPackage
                        )
                        if (!alreadyOwned && SpotifyReplayPolicy.shouldReplay(
                                snapshot,
                                session,
                                platformTrack ?: overlayTrack,
                                hostTrack,
                                generationPolicy.generation,
                                snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                                SpotifyMetadataArtwork.isReadyForLyricInfo(outgoing),
                                outgoing.getString(SpotifyPlayerConstants.METADATA_KEY_LYRIC_INFO)
                            )
                        ) {
                            SpotifyNativePublisher.buildReplayMetadata(
                                outgoing,
                                snapshot!!,
                                generationPolicy,
                                hostPackage
                            ).second?.let { outgoing = it }
                        }
                        if (outgoing !== incoming) args[0] = outgoing
                    }
                }
                }

            type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
                .apply { isAccessible = true }
                .also { method ->
                    hookRuntime.hook(method, "spotify.session.MediaSession#setPlaybackState") {
                    after {
                        val session = instanceOrNull as? MediaSession ?: return@after
                        val state = (args.getOrNull(0) as? PlaybackState)?.state
                            ?: PlaybackState.STATE_NONE
                        sessions.onPlaybackState(session, state)
                        drainPendingPublication()
                    }
                }
                }

            type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .also { method ->
                    hookRuntime.hook(method, "spotify.session.MediaSession#setActive") {
                    after {
                        val session = instanceOrNull as? MediaSession ?: return@after
                        val active = args.getOrNull(0) as? Boolean ?: false
                        sessions.onActive(session, active)
                        drainPendingPublication()
                    }
                }
                }

            type.getDeclaredMethod("release").apply { isAccessible = true }.also { method ->
                hookRuntime.hook(method, "spotify.session.MediaSession#release") {
                    before {
                        val session = instanceOrNull as? MediaSession ?: return@before
                        sessions.onReleased(session)
                        synchronized(publicationLock) {
                            if (replaySnapshot?.session?.get() === session) replaySnapshot = null
                        }
                    }
                }
            }
        }.onFailure { logFailure("session", "SESSION_HOOK_FAILED", it) }
    }

    private fun observeAuthoritativeTrack(track: TrackIdentity, source: String) {
        val previous = generationPolicy.currentTrack
        val generation = generationController.observeTrack(track)
        if (previous == null || !TrackIdentityPolicy.isSameTrack(previous, track)) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "track",
                    event = "TRACK_BOUND",
                    generation = generation,
                    trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                    reason = source,
                    durationMs = track.durationMs
                )
            )
        }
    }

    private fun startLyricsFetchIfNeeded(track: TrackIdentity, source: String) {
        val generation = generationPolicy.generation
        val acceptsPublication = generationController.acceptsPublication(track, generation)
        val fetchableIdentity = SpotifyTrackBindPolicy.hasFetchableIdentity(track)
        val gateAllows = SpotifyLyricFetchGate.shouldStartFetch(
            track,
            generation,
            fetchGeneration
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = SpotifyPlayerConstants.COMPONENT,
                area = "lyric",
                event = "LYRIC_FETCH_GATE",
                generation = generation,
                trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                reason = source,
                message = "accepts=" + acceptsPublication +
                    " fetchable=" + fetchableIdentity +
                    " gateAllows=" + gateAllows +
                    " fetchGeneration=" + fetchGeneration +
                    " headersReady=" + authHeaders.hasRequired() +
                    " headerKeys=" + authHeaders.capturedKeyList()
            )
        )
        if (!acceptsPublication || !gateAllows) return

        fetchJob?.cancel()
        fetchGeneration = generation
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = SpotifyPlayerConstants.COMPONENT,
                area = "lyric",
                event = "LYRIC_FETCH_STARTED",
                generation = generation,
                reason = source
            )
        )
        fetchJob = fetchScope.launch {
            val outcome = try {
                fetcher.fetch(hookContext, track, generation, authHeaders)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logFailure("lyric", "LYRIC_FETCH_FAILED", error)
                SpotifyFetchOutcome.Failed
            }
            mainHandler.post {
                val outcomeName = when (outcome) {
                    is SpotifyFetchOutcome.Lyrics -> "lyrics"
                    SpotifyFetchOutcome.NoLyric -> "no-lyric"
                    SpotifyFetchOutcome.HeadersMissing -> "headers-missing"
                    SpotifyFetchOutcome.Failed -> "failed"
                }
                StructuredDiagnostics.logInfo(
                    DiagnosticEvent(
                        component = SpotifyPlayerConstants.COMPONENT,
                        area = "lyric",
                        event = "LYRIC_FETCH_OUTCOME",
                        generation = generation,
                        trackHash = generationPolicy.currentTrack?.let {
                            DiagnosticHasher.sha256(it.buildStableKey())
                        },
                        reason = outcomeName,
                        message = "currentGeneration=" + generationPolicy.generation +
                            " fetchGeneration=" + fetchGeneration
                    )
                )
                if (!generationPolicy.isGenerationValid(generation)) {
                    logPublicationResult("STALE", generation)
                    return@post
                }
                if (SpotifyLyricFetchGate.shouldUnlatchAfterMissingHeaders(outcome)) {
                    if (fetchGeneration == generation) fetchGeneration = null
                    logPublicationResult("HEADERS_MISSING", generation)
                    return@post
                }
                when (outcome) {
                    is SpotifyFetchOutcome.Lyrics ->
                        handlePublication(outcome.publication.boundTo(track), allowPending = true)
                    SpotifyFetchOutcome.NoLyric -> logPublicationResult("NO_LYRIC", generation)
                    SpotifyFetchOutcome.Failed -> logPublicationResult("FETCH_FAILED", generation)
                    SpotifyFetchOutcome.HeadersMissing -> Unit
                }
            }
        }
    }

    private fun retryFetchAfterHeadersReady() {
        val track = generationPolicy.currentTrack ?: return
        val generation = generationPolicy.generation
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = SpotifyPlayerConstants.COMPONENT,
                area = "lyric",
                event = "LYRIC_FETCH_RETRY_HEADERS_READY",
                generation = generation,
                trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                reason = "headers-ready",
                message = "fetchGeneration=" + fetchGeneration +
                    " keys=" + authHeaders.capturedKeyList()
            )
        )
        if (fetchGeneration == generation) {
            fetchJob?.cancel()
            fetchJob = null
            fetchGeneration = null
        }
        startLyricsFetchIfNeeded(track, "headers-ready")
    }

    private fun handlePublication(publication: SpotifyPublication, allowPending: Boolean) {
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
        val artworkReady = SpotifyMetadataArtwork.isReadyForLyricInfo(metadata)
        val decision = SpotifyPendingPublicationPolicy.decide(
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
            SpotifyPendingPublicationPolicy.Decision.PENDING -> {
                if (!allowPending) return
                val replaced = pendingStore.replace(publication)
                val reason = if (replaced == null) "PENDING_STORED" else "PENDING_REPLACED"
                logPublicationResult(reason, generation)
            }
            SpotifyPendingPublicationPolicy.Decision.DROP_STALE -> {
                pendingStore.clear()
                logPublicationResult("STALE", generation)
            }
            SpotifyPendingPublicationPolicy.Decision.PUBLISH -> {
                val publishTrack = effectiveTrack ?: return
                val bound = publication.boundTo(publishTrack)
                val publishResult = SpotifyNativePublisher.publish(
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
                        replaySnapshot = SpotifyReplaySnapshot(
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
        if (!SpotifyMetadataArtwork.isReadyForLyricInfo(incoming)) return null
        val generation = generationPolicy.generation
        val currentTrack = generationPolicy.currentTrack ?: resolvedTrack.takeUnless { it.isBlank }
        if (currentTrack == null) return null
        if (!TrackIdentityPolicy.isSameTrack(pending.capturedTrack, currentTrack)) {
            return null
        }
        if (!generationController.acceptsPublication(currentTrack, generation)) return null
        val selected = sessions.selectUnique(currentTrack)
        if (selected !== session && selected != null) return null

        val bound = pending.boundTo(currentTrack)
        val snapshot = SpotifyReplaySnapshot(WeakReference(session), currentTrack, generation, bound)
        val (result, patched) = SpotifyNativePublisher.buildReplayMetadata(
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
                component = SpotifyPlayerConstants.COMPONENT,
                area = "publisher",
                event = "SPOTIFY_HOST_METADATA_LYRIC_INFO_ATTACHED",
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
            !TrackIdentityPolicy.isSameTrack(pending.capturedTrack, currentTrack)
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
        if (!SpotifyMetadataArtwork.isReadyForLyricInfo(metadata)) return
        handlePublication(pending.boundTo(currentTrack), allowPending = true)
    }

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(
            component = SpotifyPlayerConstants.COMPONENT,
            area = "publication",
            event = "SPOTIFY_FINAL_" + result,
            generation = generation
        )
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(
            component = SpotifyPlayerConstants.COMPONENT,
            area = area,
            event = event,
            reason = error.javaClass.simpleName,
            message = error.message?.take(1000)
        ),
        throwable = error
    )
}
