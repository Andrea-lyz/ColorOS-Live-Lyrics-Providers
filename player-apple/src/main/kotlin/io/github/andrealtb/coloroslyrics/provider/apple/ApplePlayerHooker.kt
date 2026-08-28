/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
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
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.reflection.CandidateResolver
import io.github.andrealtb.coloroslyrics.provider.reflection.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class ApplePlayerHooker(
    private val hookContext: Context,
    private val hostPackage: String,
    private val hostVersion: String?
) : YukiBaseHooker() {

    private val sessions = AppleMediaSessionRegistry()
    private val publicationLock = Any()
    private val pendingStore = ApplePendingPublicationStore()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackItemsById = object : LinkedHashMap<String, AppleCachedPlaybackItem>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AppleCachedPlaybackItem>?
        ): Boolean = size > 24
    }
    private val lyricCacheById = object : LinkedHashMap<String, ApplePublication>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ApplePublication>?): Boolean =
            size > 24
    }

    private var replaySnapshot: AppleReplaySnapshot? = null
    private var lyricRequester: AppleLyricRequester? = null
    private var diskCache: AppleDiskSongCache? = null

    @Volatile
    private var requestGeneration: Long? = null

    @Volatile
    private var requestAttempts: Int = 0

    @Volatile
    private var retryPending: Boolean = false

    @Volatile
    private var playbackItemPollPending: Boolean = false

    @Volatile
    private var playbackItemPolls: Int = 0

    private val generationController = AppleHostGenerationController { _, _ ->
        requestGeneration = null
        requestAttempts = 0
        retryPending = false
        playbackItemPollPending = false
        playbackItemPolls = 0
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
                    component = ApplePlayerConstants.COMPONENT,
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
            provider = ProviderId.APPLE,
            rootSource = YukiHookDebugSource.create(hookContext)
        )
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = ApplePlayerConstants.COMPONENT,
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
                    component = ApplePlayerConstants.COMPONENT,
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
                component = ApplePlayerConstants.COMPONENT,
                area = "bootstrap",
                event = "HOOK_INITIALIZING",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )

        diskCache = AppleDiskSongCache(hookContext)
        val application = (hookContext.applicationContext as? Application) ?: return
        val loader = appClassLoader ?: hookContext.classLoader
        lyricRequester = AppleLyricRequester(loader, application)

        installSessionHooks()
        installPlaybackItemHooks(loader)
        installLyricHooks(loader)

        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = ApplePlayerConstants.COMPONENT,
                area = "bootstrap",
                event = "PROCESS_READY",
                mode = resolution.mode,
                process = resolution.processName,
                providerVersion = hostVersion
            )
        )
    }

    private fun installPlaybackItemHooks(classLoader: ClassLoader) {
        runCatching {
            val playbackItemClass = classLoader.loadClass(ApplePlayerConstants.PLAYBACK_ITEM)
            val methods = findPlaybackItemMapperMethods(classLoader, playbackItemClass)
            if (methods.isEmpty()) {
                error("playback item mapper method not found")
            }
            methods.forEach { method ->
                method.isAccessible = true
                method.hook {
                    after {
                        onPlaybackItemObserved(result, requestIfMissing = true)
                    }
                }
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "hook",
                    event = "PLAYBACK_ITEM_MAPPER_HOOK_INSTALLED",
                    reason = methods.joinToString { it.declaringClass.name + "#" + it.name }
                )
            )
        }.onFailure { logFailure("hook", "PLAYBACK_ITEM_MAPPER_HOOK_FAILED", it) }
    }

    private fun installLyricHooks(classLoader: ClassLoader) {
        runCatching {
            val viewModelClass = classLoader.loadClass(ApplePlayerConstants.PLAYER_LYRICS_VIEW_MODEL)
            val playbackItemClass = classLoader.loadClass(ApplePlayerConstants.PLAYBACK_ITEM)
            val loadMethod = findLoadLyricsMethod(viewModelClass, playbackItemClass)
                ?: error("loadLyrics method not found")
            lyricRequester?.setLoadLyricsMethod(loadMethod)

            loadMethod.hook {
                before {
                    onPlaybackItemObserved(args.getOrNull(0), requestIfMissing = false)
                }
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "hook",
                    event = "LOAD_LYRICS_HOOK_INSTALLED",
                    reason = loadMethod.name
                )
            )
        }.onFailure { logFailure("hook", "LOAD_LYRICS_HOOK_FAILED", it) }

        runCatching {
            val viewModelClass = classLoader.loadClass(ApplePlayerConstants.PLAYER_LYRICS_VIEW_MODEL)
            val songInfoPtrClass = classLoader.loadClass(ApplePlayerConstants.SONG_INFO_PTR)
            val method = findLyricBuildMethod(viewModelClass, songInfoPtrClass)
                ?: error("buildTimeRangeToLyricsMap method not found")
            method.isAccessible = true
            method.hook {
                after {
                    val songNative = AppleNativeCalls.unwrapPtr(args.getOrNull(0)) ?: return@after
                    onLyricsBuilt(songNative)
                }
            }
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "hook",
                    event = "LYRIC_BUILD_HOOK_INSTALLED",
                    reason = method.name
                )
            )
        }.onFailure { logFailure("hook", "LYRIC_BUILD_HOOK_FAILED", it) }
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                constructor.hook {
                    after {
                        (instanceOrNull as? MediaSession)?.let {
                            sessions.onConstructed(it, AppleMediaSessionRegistry.constructorTag(args))
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
                        val platformTrack = AppleTrackIdentity.fromMetadata(incoming)
                        var outgoing = incoming
                        sessions.onHostMetadata(session, platformTrack, outgoing)
                        platformTrack?.let { observeAuthoritativeTrack(it, "metadata") }
                        requestLyricsIfNeeded("metadata")
                        val hostTrack = generationPolicy.currentTrack
                        val overlayTrack = hostTrack ?: platformTrack ?: return@before
                        attachPendingToHostMetadata(session, outgoing, overlayTrack)?.let {
                            outgoing = it
                        }

                        val snapshot = synchronized(publicationLock) { replaySnapshot }
                        val incomingLyricInfo = outgoing.getString("lyricInfo")
                        val alreadyOwned = AppleReplayPolicy.isModuleOwned(
                            incomingLyricInfo,
                            hostPackage
                        )
                        if (!alreadyOwned && AppleReplayPolicy.shouldReplay(
                                snapshot,
                                session,
                                platformTrack ?: overlayTrack,
                                hostTrack,
                                generationPolicy.generation,
                                snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                                AppleMetadataArtwork.isReadyForLyricInfo(outgoing),
                                incomingLyricInfo
                            )
                        ) {
                            AppleNativePublisher.buildReplayMetadata(
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
                    after {
                        val session = instanceOrNull as? MediaSession ?: return@after
                        val state = (args.getOrNull(0) as? PlaybackState)?.state
                            ?: PlaybackState.STATE_NONE
                        sessions.onPlaybackState(session, state)
                        drainPendingPublication()
                    }
                }

            type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .hook {
                    after {
                        val session = instanceOrNull as? MediaSession ?: return@after
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

    private fun onPlaybackItemObserved(playbackItem: Any?, requestIfMissing: Boolean) {
        val item = playbackItem ?: return
        val track = AppleTrackIdentity.fromPlaybackItem(item) ?: return
        val adamId = track.id.orEmpty()
        if (adamId.isNotBlank()) {
            synchronized(playbackItemsById) {
                playbackItemsById[adamId] = AppleCachedPlaybackItem(track, item)
            }
        }
        val authoritative = generationPolicy.currentTrack
        if (!AppleTrackBindPolicy.shouldFollowObservedPlaybackItem(authoritative, track)) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "track",
                    event = "PLAYBACK_ITEM_IGNORED",
                    generation = generationPolicy.generation,
                    reason = "observed=" + adamId +
                        " observedTitle=" + track.title.orEmpty() +
                        " authoritative=" + authoritative?.id.orEmpty() +
                        " authoritativeTitle=" + authoritative?.title.orEmpty()
                )
            )
            return
        }
        observeAuthoritativeTrack(track, "playback-item")
        if (requestIfMissing) {
            requestLyricsIfNeeded("playback-item")
        }
    }

    private fun observeAuthoritativeTrack(track: TrackIdentity, source: String) {
        val previous = generationPolicy.currentTrack
        val generation = generationController.observeTrack(track)
        if (previous == null || !TrackIdentityPolicy.isSameTrack(previous, track)) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "track",
                    event = "TRACK_BOUND",
                    generation = generation,
                    trackHash = DiagnosticHasher.sha256(track.buildStableKey()),
                    reason = source,
                    durationMs = track.durationMs
                )
            )
            publishCachedIfAvailable(track, generation)
        }
    }

    private fun publishCachedIfAvailable(track: TrackIdentity, generation: Long) {
        val adamId = resolveAdamId(track) ?: return
        val cached = synchronized(lyricCacheById) { lyricCacheById[adamId] }
            ?: diskCache?.load(adamId)?.let { song ->
                val lines = AppleSongMapper.toRichLines(song)
                if (lines.isEmpty()) return@let null
                ApplePublication(lines, track, "apple-disk-cache").also { publication ->
                    synchronized(lyricCacheById) { lyricCacheById[adamId] = publication }
                }
            }
        if (cached == null) return
        handlePublication(cached.boundTo(track), allowPending = true)
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = ApplePlayerConstants.COMPONENT,
                area = "lyric",
                event = "LYRIC_CACHE_HIT",
                generation = generation,
                reason = adamId
            )
        )
    }

    private fun requestLyricsIfNeeded(source: String) {
        val track = generationPolicy.currentTrack ?: return
        val generation = generationPolicy.generation
        if (!generationController.acceptsPublication(track, generation)) return
        val playbackItem = findPlaybackItem(track)
        AppleTrackIdentity.fromPlaybackItem(playbackItem)?.let { itemTrack ->
            observeAuthoritativeTrack(itemTrack, source)
        }
        val boundTrack = generationPolicy.currentTrack ?: track
        val adamId = resolveAdamId(boundTrack).orEmpty()
        if (adamId.isNotEmpty() && synchronized(lyricCacheById) { lyricCacheById[adamId] } != null) {
            publishCachedIfAvailable(boundTrack, generation)
            return
        }
        if (!AppleLyricRequestGate.shouldStartRequest(
                boundTrack,
                generation,
                requestGeneration,
                playbackItem != null
            )
        ) {
            if (playbackItem == null && AppleTrackBindPolicy.hasRequestableIdentity(boundTrack)) {
                StructuredDiagnostics.logDebug(
                    DiagnosticEvent(
                        component = ApplePlayerConstants.COMPONENT,
                        area = "lyric",
                        event = "WAIT_PLAYBACK_ITEM",
                        generation = generation,
                        trackHash = DiagnosticHasher.sha256(boundTrack.buildStableKey()),
                        reason = source
                    )
                )
                scheduleFollowUp(boundTrack, generation, "playback-item-poll")
            }
            return
        }
        if (playbackItem == null) return
        requestGeneration = generation
        requestAttempts++
        val accepted = lyricRequester?.requestDownload(playbackItem) == true
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = ApplePlayerConstants.COMPONENT,
                area = "lyric",
                event = "LYRIC_REQUESTED",
                generation = generation,
                reason = source + " attempt=" + requestAttempts + " accepted=" + accepted
            )
        )
        scheduleFollowUp(boundTrack, generation, "retry")
    }

    private fun scheduleFollowUp(track: TrackIdentity, generation: Long, source: String) {
        val delayMs = when (source) {
            "retry" -> {
                if (retryPending) return
                if (!AppleLyricRequestGate.shouldRetry(requestAttempts, hasLyrics = false)) return
                retryPending = true
                AppleLyricRequestGate.retryDelayMs(requestAttempts)
            }
            "playback-item-poll" -> {
                if (playbackItemPollPending) return
                if (!AppleLyricRequestGate.shouldPollForPlaybackItem(playbackItemPolls)) return
                playbackItemPollPending = true
                playbackItemPolls++
                AppleLyricRequestGate.PLAYBACK_ITEM_POLL_DELAY_MS
            }
            else -> return
        }
        mainHandler.postDelayed({
            if (source == "retry") {
                retryPending = false
            } else {
                playbackItemPollPending = false
            }
            if (!sameTrackFollowUpStillValid(track, generation)) return@postDelayed
            if (source == "retry") requestGeneration = null
            requestLyricsIfNeeded(source)
        }, delayMs)
    }

    private fun sameTrackFollowUpStillValid(track: TrackIdentity, generation: Long): Boolean {
        val current = generationPolicy.currentTrack ?: return false
        if (generationPolicy.generation != generation) return false
        if (!TrackIdentityPolicy.isSameTrack(current, track)) return false
        val adamId = resolveAdamId(current)
        return adamId == null || synchronized(lyricCacheById) { lyricCacheById[adamId] } == null
    }

    private fun findPlaybackItem(track: TrackIdentity): Any? {
        synchronized(playbackItemsById) {
            return AppleTrackBindPolicy.findCachedPlaybackItem(track, playbackItemsById)?.item
        }
    }

    private fun resolveAdamId(track: TrackIdentity): String? {
        track.id?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return AppleTrackIdentity.fromPlaybackItem(findPlaybackItem(track))
            ?.id
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun onLyricsBuilt(songNative: Any) {
        val language = AppleLocaleUtil.systemLyricsLanguage(
            appClassLoader ?: hookContext.classLoader
        )
        AppleSongParser.applySystemTranslation(songNative, language)
        val parsed = AppleSongParser.parse(songNative) ?: return
        val current = generationPolicy.currentTrack
        val parsedTrack = TrackIdentity(
            id = parsed.adamId,
            title = parsed.name,
            artist = parsed.artist,
            durationMs = parsed.durationMs
        )
        val enriched = if (current != null && AppleTrackBindPolicy.unnamedOrSame(current, parsedTrack)) {
            parsed.copy(
                name = current.title ?: parsed.name,
                artist = current.artist ?: parsed.artist,
                durationMs = current.durationMs.takeIf { it > 0 } ?: parsed.durationMs
            )
        } else {
            parsed
        }
        diskCache?.save(enriched)
        val lines = AppleSongMapper.toRichLines(enriched)
        if (lines.isEmpty()) {
            logPublicationResult("NO_LYRIC", generationPolicy.generation)
            return
        }
        val publication = ApplePublication(
            lines = lines,
            capturedTrack = TrackIdentity(
                id = enriched.adamId,
                title = enriched.name,
                artist = enriched.artist,
                durationMs = enriched.durationMs
            ),
            sourceName = "apple-ttml"
        )
        synchronized(lyricCacheById) {
            lyricCacheById[enriched.adamId] = publication
        }
        val hostTrack = generationPolicy.currentTrack
        val liveTrack = (sessions.uniqueLiveSession() as? MediaSession)
            ?.controller
            ?.metadata
            ?.let { AppleTrackIdentity.fromMetadata(it) }
        if (hostTrack == null ||
            !AppleTrackBindPolicy.unnamedOrSame(hostTrack, parsedTrack) ||
            !AppleTrackBindPolicy.unnamedOrSame(liveTrack, parsedTrack)
        ) {
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = ApplePlayerConstants.COMPONENT,
                    area = "lyric",
                    event = "LYRIC_BUILT_STALE_OR_PREFETCH",
                    generation = generationPolicy.generation,
                    reason = "adamId=" + enriched.adamId +
                        " current=" + hostTrack?.id.orEmpty() +
                        " currentTitle=" + hostTrack?.title.orEmpty() +
                        " liveTitle=" + liveTrack?.title.orEmpty()
                )
            )
            return
        }
        handlePublication(publication.boundTo(hostTrack), allowPending = true)
    }

    private fun handlePublication(publication: ApplePublication, allowPending: Boolean) {
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
        val liveTrack = AppleTrackIdentity.fromMetadata(metadata)
        val artworkReady = AppleMetadataArtwork.isReadyForLyricInfo(metadata)
        val decision = ApplePendingPublicationPolicy.decide(
            publicationTrack = hinted,
            currentHostTrack = currentTrack,
            liveSessionTrack = liveTrack,
            generationValid = currentTrack != null && generationController.acceptsPublication(
                currentTrack,
                generation
            ),
            uniqueSessionReady = session != null,
            metadataReady = metadata != null,
            artworkReady = artworkReady
        )

        when (decision) {
            ApplePendingPublicationPolicy.Decision.PENDING -> {
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
            ApplePendingPublicationPolicy.Decision.DROP_STALE -> {
                pendingStore.clear()
                logPublicationResult("STALE", generation)
            }
            ApplePendingPublicationPolicy.Decision.PUBLISH -> {
                val publishTrack = effectiveTrack ?: return
                val bound = publication.boundTo(publishTrack)
                val publishResult = AppleNativePublisher.publish(
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
                        replaySnapshot = AppleReplaySnapshot(
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
        if (!AppleMetadataArtwork.isReadyForLyricInfo(incoming)) return null
        val generation = generationPolicy.generation
        val currentTrack = generationPolicy.currentTrack ?: resolvedTrack.takeUnless { it.isBlank }
        if (currentTrack == null) return null
        if (!TrackIdentityPolicy.isSameTrack(pending.capturedTrack, currentTrack)) {
            return null
        }
        val liveTrack = AppleTrackIdentity.fromMetadata(incoming)
        if (!AppleTrackBindPolicy.unnamedOrSame(liveTrack, pending.capturedTrack ?: currentTrack)) {
            return null
        }
        if (!generationController.acceptsPublication(currentTrack, generation)) return null
        val selected = sessions.selectUnique(currentTrack)
        if (selected !== session && selected != null) return null

        val bound = pending.boundTo(currentTrack)
        val snapshot = AppleReplaySnapshot(WeakReference(session), currentTrack, generation, bound)
        val (result, patched) = AppleNativePublisher.buildReplayMetadata(
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
                component = ApplePlayerConstants.COMPONENT,
                area = "publisher",
                event = "APPLE_HOST_METADATA_LYRIC_INFO_ATTACHED",
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
        if (!AppleMetadataArtwork.isReadyForLyricInfo(metadata)) return
        handlePublication(pending.boundTo(currentTrack), allowPending = true)
    }

    private fun findLoadLyricsMethod(
        viewModelClass: Class<*>,
        playbackItemClass: Class<*>
    ): Method? {
        val named = viewModelClass.declaredMethods.filter { method ->
            method.name == ApplePlayerConstants.LOAD_LYRICS &&
                method.parameterCount == 1 &&
                playbackItemClass.isAssignableFrom(method.parameterTypes[0])
        }
        if (named.isNotEmpty()) {
            return CandidateResolver.resolveUniqueMethod(
                named,
                "${viewModelClass.name}#${ApplePlayerConstants.LOAD_LYRICS}",
                hostVersion = hostVersion
            )
        }
        val structural = viewModelClass.declaredMethods.filter { method ->
            method.parameterCount == 1 &&
                playbackItemClass.isAssignableFrom(method.parameterTypes[0]) &&
                method.returnType == Void.TYPE
        }
        return CandidateResolver.resolveUniqueMethod(
            structural,
            "${viewModelClass.name}#loadLyrics",
            "single void method accepting PlaybackItem",
            hostVersion
        )
    }

    private fun findLyricBuildMethod(
        viewModelClass: Class<*>,
        songInfoPtrClass: Class<*>
    ): Method? {
        runCatching {
            viewModelClass.getDeclaredMethod(
                ApplePlayerConstants.BUILD_TIME_RANGE_TO_LYRICS_MAP,
                songInfoPtrClass
            )
        }.getOrNull()?.let { return it }
        val structural = viewModelClass.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == songInfoPtrClass &&
                method.returnType == Void.TYPE
        }
        return CandidateResolver.resolveUniqueMethod(
            structural,
            "${viewModelClass.name}#${ApplePlayerConstants.BUILD_TIME_RANGE_TO_LYRICS_MAP}",
            "single instance void method accepting SongInfoPtr",
            hostVersion
        )
    }

    private fun findPlaybackItemMapperMethods(
        classLoader: ClassLoader,
        playbackItemClass: Class<*>
    ): List<Method> {
        val candidates = sequence {
            yield(ApplePlayerConstants.MAPPER_FALLBACK_N)
            yield(ApplePlayerConstants.MAPPER_FALLBACK_M)
            findPlaybackItemMapperClassesByDexKit(classLoader).forEach { yield(it.name) }
        }.distinct()

        return candidates
            .mapNotNull { className ->
                runCatching { classLoader.loadClass(className) }.getOrNull()
            }
            .flatMap { mapperClass -> mapperClass.playbackItemMapperMethods(playbackItemClass) }
            .distinctBy { "${it.declaringClass.name}#${it.name}${it.parameterTypes.joinToString()}" }
            .onEach { it.isAccessible = true }
            .toList()
    }

    private fun findPlaybackItemMapperClassesByDexKit(classLoader: ClassLoader): List<Class<*>> {
        return runCatching {
            DexKitBridge.withDexKit(hookContext.applicationInfo.sourceDir) { bridge ->
                bridge.findClass {
                    searchPackages(ApplePlayerConstants.MAPPER_SEARCH_PACKAGE)
                    matcher {
                        usingStrings(
                            ApplePlayerConstants.METADATA_KEY_MEDIA_ID,
                            ApplePlayerConstants.METADATA_KEY_PLAYBACK_ENDPOINT_TYPE
                        )
                    }
                }.mapNotNull { classData ->
                    runCatching { classData.getInstance(classLoader) }.getOrNull()
                }
            }
        }.onFailure {
            logFailure("hook", "DEXKIT_MAPPER_SEARCH_FAILED", it)
        }.getOrDefault(emptyList())
    }

    private fun Class<*>.playbackItemMapperMethods(playbackItemClass: Class<*>): List<Method> =
        declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                playbackItemClass.isAssignableFrom(method.returnType) &&
                method.parameterCount == 1
        }

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(
            component = ApplePlayerConstants.COMPONENT,
            area = "publication",
            event = "APPLE_FINAL_" + result,
            generation = generation
        )
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(
            component = ApplePlayerConstants.COMPONENT,
            area = area,
            event = event,
            reason = error.javaClass.simpleName,
            message = error.message?.take(1000)
        ),
        throwable = error
    )
}
