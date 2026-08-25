/* Copyright 2026 Andrea-TB */
package io.github.andrealtb.coloroslyrics.provider.salt

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeMode
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionCache
import java.lang.ref.WeakReference

class SaltPlayerHooker(private val hookContext: Context, private val hostVersion: String?) : YukiBaseHooker() {
    private val sessions = SaltMediaSessionRegistry()
    private val dexKitLock = Any()
    private val publicationLock = Any()
    private val mediaButtonLock = Any()
    private val reflectionCache = ReflectionCache(hookContext.classLoader, hostVersion)
    private val pendingStore = SaltPendingPublicationStore()
    @Volatile private var mediaButtonHookInstalled = false
    private var replaySnapshot: SaltReplaySnapshot? = null
    private val generationController = SaltHostGenerationController(sessions) {
        synchronized(publicationLock) { replaySnapshot = null }
    }
    private val generationPolicy: TrackGenerationPolicy
        get() = generationController.policy
    private var resolvedSongClass: Class<*>? = null
    private var resolvedResultClass: Class<*>? = null
    private var resolvedSourceEnumClass: Class<*>? = null
    private var resolvedSongAccessors: SaltSongAccessors? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onHook() {
        val resolution = RuntimeModeResolver.resolve(hookContext)
        if (!resolution.mode.isSupported) {
            StructuredDiagnostics.logWarning(DiagnosticEvent(
                component = "provider/salt", area = "bootstrap", event = "HOOK_DISABLED",
                mode = resolution.mode, process = resolution.processName, reason = resolution.markerSource
            ))
            return
        }
        ProviderDebugConfig.configureDiagnostics(
            mode = resolution.mode,
            provider = ProviderId.SALT,
            rootSource = if (resolution.mode == RuntimeMode.ROOT_MODULE) SaltRootDebugSource.create() else null,
            embeddedSource = if (resolution.mode == RuntimeMode.NPATCH_EMBEDDED) {
                ProviderDebugConfig.embeddedMarkerSource(hookContext)
            } else null
        )
        installMediaButtonHook()
        installSessionHooks()
        installPublisherHook()
    }

    private fun installMediaButtonHook() {
        if (mediaButtonHookInstalled) return
        synchronized(mediaButtonLock) {
            if (mediaButtonHookInstalled) return
            runCatching {
                val receiver = hookContext.classLoader.loadClass(SaltPlayerConstants.MEDIA_BUTTON_RECEIVER_CLASS)
                val onReceive = receiver.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
                onReceive.isAccessible = true
                onReceive.hook {
                    before {
                        val context = args.getOrNull(0) as? Context ?: return@before
                        val intent = args.getOrNull(1) as? Intent ?: return@before
                        if (!SaltMediaButtonPolicy.isPlayMediaButtonIntent(intent)) return@before
                        if (!SaltMediaButtonPolicy.shouldAcceptMediaButtonStart()) {
                            resultNull()
                            return@before
                        }
                        val app = context.applicationContext ?: context
                        SaltMediaButtonPolicy.startSaltService(app, null)
                        mainHandler.postDelayed({
                            SaltMediaButtonPolicy.startSaltService(app, SaltPlayerConstants.ACTION_PLAY_OR_PAUSE)
                        }, SaltPlayerConstants.PLAY_AFTER_SERVICE_START_DELAY_MS)
                        resultNull()
                    }
                }
                mediaButtonHookInstalled = true
            }.onFailure { logFailure("media-button", "MEDIA_BUTTON_HOOK_FAILED", it) }
        }
    }

    private fun installPublisherHook() {
        runCatching {
            reflectionCache.ensureValid(hookContext.classLoader, hostVersion)
            val discovery = synchronized(dexKitLock) {
                SaltDexKitDiscovery.discover(hookContext.applicationInfo.sourceDir, hookContext.classLoader)
            }
            val sourceEnumClass = discovery.sourceEnum.getInstance(hookContext.classLoader)
            val resultClass = discovery.lyricResult.getInstance(hookContext.classLoader)
            val publisherClass = discovery.publisher.getInstance(hookContext.classLoader)
            val songClass = reflectionCache.getOrPutClass("salt.song") {
                hookContext.classLoader.loadClass(SaltPlayerConstants.SALT_SONG_CLASS)
            }
            val publicationMethod = reflectionCache.getOrPutMethod("salt.publisher.${publisherClass.name}") {
                SaltPublisherMethodResolver.findInvokeSuspendMethod(publisherClass)
            }.apply {
                isAccessible = true
            }
            val accessors = SaltSongAccessors(
                id = reflectionCache.getOrPutMethod("salt.song.getId") { songClass.getMethod("getId") },
                title = reflectionCache.getOrPutMethod("salt.song.getTitle") { songClass.getMethod("getTitle") },
                artist = reflectionCache.getOrPutMethod("salt.song.getArtist") { songClass.getMethod("getArtist") },
                album = runCatching {
                    reflectionCache.getOrPutMethod("salt.song.getAlbum") { songClass.getMethod("getAlbum") }
                }.getOrNull(),
                duration = runCatching {
                    reflectionCache.getOrPutMethod("salt.song.getDuration") { songClass.getMethod("getDuration") }
                }.getOrNull()
            )
            synchronized(publicationLock) {
                resolvedSongClass = songClass
                resolvedResultClass = resultClass
                resolvedSourceEnumClass = sourceEnumClass
                resolvedSongAccessors = accessors
            }
            publicationMethod.hook { after { instanceOrNull?.let(::onFinalLyricPublication) } }
            StructuredDiagnostics.logInfo(DiagnosticEvent(
                component = "provider/salt", area = "reflection", event = "PUBLISHER_HOOK_INSTALLED",
                reason = "publisher=${publisherClass.name}#${publicationMethod.name}"
            ))
        }.onFailure { logFailure("reflection", "PUBLISHER_HOOK_FAILED", it) }
    }

    private fun onFinalLyricPublication(publisher: Any) {
        runCatching {
            val songClass = resolvedSongClass ?: return
            val resultClass = resolvedResultClass ?: return
            val sourceEnumClass = resolvedSourceEnumClass ?: return
            val song = SaltLyricDecoder.findFieldValueOfType(publisher, songClass) ?: return
            val result = SaltLyricDecoder.findFieldValueOfType(publisher, resultClass) ?: return
            val accessors = resolvedSongAccessors ?: return
            val decodedSong = SaltLyricDecoder.decodeSong(song, accessors) ?: return
            val publication = SaltLyricDecoder.merge(decodedSong, SaltLyricDecoder.decodeResult(result, sourceEnumClass))
            handlePublication(publication, allowPending = true)
        }.onFailure { logFailure("publication", "PUBLICATION_DECODE_FAILED", it) }
    }

    private fun installSessionHooks() {
        runCatching {
            val type = MediaSession::class.java
            type.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                constructor.hook { after {
                    (instanceOrNull as? MediaSession)?.let {
                        sessions.onConstructed(it, SaltMediaSessionRegistry.constructorTag(args))
                    }
                } }
            }
            type.getDeclaredMethod("setMetadata", MediaMetadata::class.java).apply { isAccessible = true }.hook {
                before {
                    if (sessions.isModuleWrite()) return@before
                    val session = instanceOrNull as? MediaSession ?: return@before
                    val incoming = args.getOrNull(0) as? MediaMetadata ?: return@before
                    val resolved = SaltBluetoothLyricRelayPolicy.resolve(incoming) ?: return@before
                    SaltBluetoothLyricRelayPolicy.logResolved(resolved)
                    sessions.onHostMetadata(session, resolved.track, incoming)
                    observeGenerationFromHostMainSession()
                    attachPendingToHostMetadata(session, incoming)?.let { args[0] = it }
                    val snapshot = synchronized(publicationLock) { replaySnapshot }
                    val selected = sessions.selectUnique(resolved.track)
                    val replayBase = args.getOrNull(0) as? MediaMetadata ?: incoming
                    val incomingLyricInfo = replayBase.getString("lyricInfo")
                    val alreadyOwned = SaltReplayPolicy.isModuleOwned(incomingLyricInfo)
                    if (!alreadyOwned && SaltReplayPolicy.shouldReplay(
                            snapshot, selected, resolved.track, generationPolicy.currentTrack,
                            generationPolicy.generation,
                            snapshot?.let { generationPolicy.isGenerationValid(it.generation) } == true,
                            incomingLyricInfo
                        )) {
                        SaltNativePublisher.buildReplayMetadata(replayBase, snapshot!!, generationPolicy)
                            .second?.let { args[0] = it }
                    }
                }
            }
            type.getDeclaredMethod("setPlaybackState", PlaybackState::class.java).apply { isAccessible = true }.hook {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    sessions.onPlaybackState(session, (args.getOrNull(0) as? PlaybackState)?.state ?: PlaybackState.STATE_NONE)
                    observeGenerationFromHostMainSession()
                    drainPendingPublication()
                }
            }
            type.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.hook {
                before {
                    val session = instanceOrNull as? MediaSession ?: return@before
                    sessions.onActive(session, args.getOrNull(0) as? Boolean ?: false)
                    observeGenerationFromHostMainSession()
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

    private fun observeGenerationFromHostMainSession() {
        generationController.observeUniqueHostMainTrack()
    }

    private fun handlePublication(publication: SaltPublication, allowPending: Boolean) {
        val track = publication.trackIdentity()
        val currentTrack = generationPolicy.currentTrack
        val generation = generationPolicy.generation
        val session = sessions.selectUnique(track) as? MediaSession
        val metadata = session?.let { sessions.hostMetadata(it) as? MediaMetadata }
        val decision = SaltPendingPublicationPolicy.decide(
            publicationTrack = track,
            currentHostTrack = currentTrack,
            generationValid = generationController.acceptsPublication(track, generation),
            uniqueSessionReady = session != null,
            metadataReady = metadata != null
        )
        when (decision) {
            SaltPendingPublicationPolicy.Decision.PENDING -> {
                if (!allowPending) return
                val replaced = pendingStore.replace(publication)
                logPublicationResult(if (replaced == null) "PENDING_STORED" else "PENDING_REPLACED", generation)
            }
            SaltPendingPublicationPolicy.Decision.DROP_STALE -> {
                val pending = pendingStore.peek()
                if (pending != null && currentTrack != null &&
                    !TrackIdentityPolicy.isSameTrack(pending.trackIdentity(), currentTrack)) {
                    pendingStore.clear()
                    logPublicationResult("PENDING_DROPPED_TRACK_CHANGE", generation)
                }
                logPublicationResult("STALE", generation)
            }
            SaltPendingPublicationPolicy.Decision.PUBLISH -> {
                val publishResult = SaltNativePublisher.publish(
                    session!!, metadata!!, publication, generation, generationPolicy, sessions
                )
                if (publishResult.isPublished) synchronized(publicationLock) {
                    replaySnapshot = SaltReplaySnapshot(WeakReference(session), track, generation, publication)
                }
                logPublicationResult(publishResult.name, generation)
            }
        }
    }

    private fun attachPendingToHostMetadata(
        session: MediaSession,
        incoming: MediaMetadata
    ): MediaMetadata? {
        val pending = pendingStore.peek() ?: return null
        val track = pending.trackIdentity()
        val generation = generationPolicy.generation
        if (!generationController.acceptsPublication(track, generation)) return null
        if (sessions.selectUnique(track) !== session) return null

        val snapshot = SaltReplaySnapshot(WeakReference(session), track, generation, pending)
        val (result, patched) = SaltNativePublisher.buildReplayMetadata(
            incoming,
            snapshot,
            generationPolicy
        )
        if (!result.isPublished || patched == null) return null
        if (!pendingStore.takeIfSame(pending)) return null

        synchronized(publicationLock) {
            replaySnapshot = snapshot
        }
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/salt",
                area = "publisher",
                event = "SALT_HOST_METADATA_LYRIC_INFO_ATTACHED",
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

    private fun SaltPublication.trackIdentity() = TrackIdentity(
        id = songId.takeIf(String::isNotBlank), title = title.takeIf(String::isNotBlank),
        artist = artist.takeIf(String::isNotBlank), album = album.takeIf(String::isNotBlank),
        durationMs = durationMs
    )

    private fun logPublicationResult(result: String, generation: Long) = StructuredDiagnostics.logInfo(
        DiagnosticEvent(component = "provider/salt", area = "publication", event = "SALT_FINAL_$result", generation = generation)
    )

    private fun logFailure(area: String, event: String, error: Throwable) = StructuredDiagnostics.logError(
        DiagnosticEvent(component = "provider/salt", area = area, event = event, reason = error.javaClass.simpleName)
    )
}
