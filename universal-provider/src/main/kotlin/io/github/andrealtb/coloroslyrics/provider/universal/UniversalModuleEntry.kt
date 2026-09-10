package io.github.andrealtb.coloroslyrics.provider.universal

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserHandle
import android.os.SystemClock
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.universal.session.ActiveSessionSelector
import io.github.andrealtb.coloroslyrics.provider.universal.session.BluetoothLyricDemux
import io.github.andrealtb.coloroslyrics.provider.universal.session.PlaybackStates
import io.github.andrealtb.coloroslyrics.provider.universal.session.RawSessionMetadata
import io.github.andrealtb.coloroslyrics.provider.universal.session.ResolvedSession
import io.github.andrealtb.coloroslyrics.provider.universal.session.SessionObservation
import io.github.andrealtb.coloroslyrics.provider.universal.session.SessionRepository
import io.github.andrealtb.coloroslyrics.provider.universal.session.TrackIdentityResolver
import io.github.andrealtb.coloroslyrics.provider.universal.cache.UniversalLyricCache
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.json.JSONObject

class UniversalModuleEntry : XposedModule() {
    companion object {
        private val DEFAULT_BOUND_PACKAGES = setOf(
            "com.salt.music",
            "com.apple.android.music",
            "com.spotify.music",
            "remix.myplayer",
            "com.maxmpz.audioplayer",
            "ink.trantor.coneplayer"
        )
        private const val BINDINGS_FILE_PATH = "/data/system/universal_bindings.json"
        private const val APP_BINDINGS_FILE_PATH =
            "/data/user/0/io.github.andrealtb.coloroslyrics.provider.universal/files/universal_bound_packages.json"
    }

    private val repository = SessionRepository(
        resolver = TrackIdentityResolver(),
        selector = ActiveSessionSelector()
    )
    @Volatile
    private var latestSnapshotText: String? = null
    @Volatile
    private var lastBroadcastSnapshotText: String? = null
    @Volatile
    private var lastBroadcastArtworkRevision = 0L
    private val configExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "UniversalConfigWriter").apply { isDaemon = true }
    }
    @Volatile
    private var receiverRegistered = false
    private var lastSelectedInstanceId: String? = null
    private var lastGeneration: Long? = null
    private val isOverriding = ThreadLocal<Boolean>()
    @Volatile
    private var boundPackages: Set<String> = emptySet()
    @Volatile
    private var cachedMetadataField: Field? = null
    @Volatile
    private var cachedLockField: Field? = null
    @Volatile
    private var cachedPushMetadataUpdateMethod: Method? = null
    private val realLyricsByPackage = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val activeRecords = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "UniversalLyricWorker").apply { isDaemon = true }
    }
    /**
     * SystemUI creates its media card asynchronously after the first
     * MediaSession callback.  A metadata push that happens before that card
     * exists can be missed, which is why a cold-start track used to require a
     * manual skip.  Keep a tiny, bounded replay queue for the freshly fetched
     * payload; each replay is generation guarded and therefore cannot leak a
     * previous song into the current session.
     */
    private val metadataReplayExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "UniversalLyricMetadataReplay").apply { isDaemon = true }
    }
    private val aggregator by lazy { io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator() }
    @Volatile
    private var sourcePriority: List<io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId> =
        io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore.decode(loadSourceConfig()).mapNotNull {
            runCatching { io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId.valueOf(it) }.getOrNull()
        }
    @Volatile
    private var wordTimingEnabled: Boolean = loadWordTimingEnabled()
    @Volatile
    private var translationEnabled: Boolean = loadTranslationEnabled()
    @Volatile
    private var rawLyricEnabled: Boolean = loadRawLyricEnabled()
    @Volatile
    private var debugEnabled: Boolean = loadDebugEnabled()
    private val fetchBackoffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private data class ArtworkSnapshot(
        val source: java.lang.ref.WeakReference<android.graphics.Bitmap>,
        val sourceGenerationId: Int,
        val bitmap: android.graphics.Bitmap,
        val revision: Long
    )
    private val nextArtworkRevision = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val artworkByPackage = java.util.concurrent.ConcurrentHashMap<String, ArtworkSnapshot>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        StructuredDiagnostics.configure(debugEnabled = debugEnabled)
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = UniversalDiagnostics.COMPONENT,
                area = "bootstrap",
                event = "MODULE_LOADED",
                process = param.processName,
                reason = "framework=${frameworkName} api=${apiVersion}"
            )
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        val stubClass = classLoader.loadClass("com.android.server.media.MediaSessionRecord\$SessionStub")
        val recordClass = classLoader.loadClass("com.android.server.media.MediaSessionRecord")
        val setMetadata = stubClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java,
            java.lang.Long.TYPE,
            String::class.java
        )
        val setPlaybackState = stubClass.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java
        )
        val recordField = stubClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val packageField = fieldOrNull(recordClass, "mPackageName")
        val userField = fieldOrNull(recordClass, "mUserId")
        val metadataField = fieldOrNull(recordClass, "mMetadata")
        val playbackField = fieldOrNull(recordClass, "mPlaybackState")
        val contextField = fieldOrNull(recordClass, "mContext")
        val lockField = fieldOrNull(recordClass, "mLock")
        val pushMetadataUpdateMethod = runCatching {
            recordClass.getDeclaredMethod("pushMetadataUpdate").apply { isAccessible = true }
        }.getOrNull()
        val pushSessionDestroyedMethod = runCatching {
            recordClass.getDeclaredMethod("pushSessionDestroyed").apply { isAccessible = true }
        }.getOrNull()
        boundPackages = loadBoundPackages()
        repository.updateBoundFilter(boundPackages, SystemClock.elapsedRealtime())
        currentSystemContext()?.let(::ensureRequestReceiver)
        currentSystemContext()?.let { UniversalBridgeBindingNotifier.notify(it, boundPackages) }
        hook(setMetadata)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("universal:setMetadata")
            .intercept { chain ->
                val stub = chain.thisObject
                val record = recordField.get(stub) ?: return@intercept chain.proceed()
                val ownerPackage = packageField?.get(record) as? String ?: return@intercept chain.proceed()
                val commandContext = (contextField?.get(record) as? Context) ?: currentSystemContext()
                commandContext?.let(::ensureRequestReceiver)
                val incoming = chain.getArg(0) as? MediaMetadata
                UniversalDiagnostics.metadataHookEntered(
                    ownerPackage,
                    incoming != null,
                    incoming?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    incoming?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    incoming?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    chain.getArg(2) as? String
                )
                if (!boundPackages.contains(ownerPackage)) {
                    refreshBindingsFromDisk()
                    if (!boundPackages.contains(ownerPackage)) {
                        UniversalDiagnostics.metadataFiltered(ownerPackage, boundPackages.size)
                        return@intercept chain.proceed()
                    }
                }
                cachedMetadataField = metadataField
                cachedLockField = lockField
                cachedPushMetadataUpdateMethod = pushMetadataUpdateMethod
                activeRecords[ownerPackage] = record

                if (incoming != null) {
                    try {
                        val rewritten = enrichMetadataBeforeSet(
                            record,
                            ownerPackage,
                            incoming,
                            chain.getArg(2) as? String,
                            userField,
                            playbackField,
                            contextField
                        )
                        if (rewritten != null) {
                            val newArgs = chain.args.toTypedArray()
                            newArgs[0] = rewritten
                            return@intercept chain.proceed(newArgs)
                        }
                    } catch (error: Throwable) {
                        UniversalDiagnostics.metadataHookFailed(ownerPackage, error)
                    }
                }
                chain.proceed()
            }
        hookAfter(setPlaybackState, "setPlaybackState") {
            val record = recordField.get(it) ?: return@hookAfter
            publish(record, packageField, userField, metadataField, playbackField, contextField, lockField, pushMetadataUpdateMethod)
        }
        if (pushSessionDestroyedMethod != null) {
            hookAfter(pushSessionDestroyedMethod, "pushSessionDestroyed") {
                val record = it
                val sessionId = System.identityHashCode(record).toString(16)
                repository.drop(sessionId, SystemClock.elapsedRealtime())
            }
        }
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = UniversalDiagnostics.COMPONENT,
                area = "bootstrap",
                event = "SYSTEM_SERVER_HOOKED",
                process = "system_server",
                reason = "record=${recordClass.name}"
            )
        )
    }

    private fun hookAfter(method: Method, id: String, block: (Any?) -> Unit) {
        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("universal:$id")
            .intercept(AfterHooker(block))
    }

    private class AfterHooker(private val block: (Any?) -> Unit) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            block(chain.thisObject)
            return result
        }
    }

    private fun publish(
        stub: Any,
        packageField: Field?,
        userField: Field?,
        metadataField: Field?,
        playbackField: Field?,
        contextField: Field?,
        lockField: Field?,
        pushMetadataUpdateMethod: Method?
    ) {
        val record = stub
        val ownerPackage = packageField?.get(record) as? String ?: return
        cachedMetadataField = metadataField
        cachedLockField = lockField
        cachedPushMetadataUpdateMethod = pushMetadataUpdateMethod
        activeRecords[ownerPackage] = record

        val context = (contextField?.get(record) as? Context) ?: currentSystemContext()
        context?.let(::ensureRequestReceiver)

        // GATE CHECK: If package is not in user's bound player list, completely ignore it.
        // This prevents video apps (Bilibili, YouTube) from being tracked or hijacking lyrics!
        if (!boundPackages.contains(ownerPackage)) {
            return
        }

        val metadata = metadataField?.get(record) as? MediaMetadata
        metadata?.let { rememberArtwork(ownerPackage, it) }
        val playback = playbackField?.get(record) as? PlaybackState
        val state = playback?.state
        val observation = SessionObservation(
            userId = (userField?.get(record) as? Int) ?: 0,
            ownerPackage = ownerPackage,
            sessionInstanceId = System.identityHashCode(record).toString(16),
            raw = RawSessionMetadata(
                mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                displayTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                displaySubtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ,playbackQueueItemId = playback?.activeQueueItemId?.takeIf { it >= 0L }
                ,playbackExtrasMediaId = playbackExtrasMediaId(playback)
                ,mediaUri = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ,positionMs = playback?.position
            ),
            playbackState = state,
            playing = PlaybackStates.isPlaying(state),
            observedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        repository.setNotificationAccessGranted(true)
        repository.onListenerConnected()
        repository.upsert(observation, boundPackages)
        val snapshot = repository.snapshot()
        val formatted = format(snapshot.selected, snapshot.sessions.size, snapshot.selectionRevision)
        latestSnapshotText = formatted

        if (context != null) {
            sendSnapshotBroadcast(context, formatted)
        }

        val recordSession = repository.get(observation.sessionInstanceId)
        if (recordSession != null) {
            UniversalDiagnostics.metadataObserved(recordSession, "after-setPlaybackState")
            val currentGen = recordSession.descriptor.trackGeneration
            realLyricsByPackage.keys.filter { it.startsWith("$ownerPackage|") && it != "$ownerPackage|$currentGen" }.forEach {
                realLyricsByPackage.remove(it)
            }
            overlayMetadata(record, recordSession, metadataField, lockField, pushMetadataUpdateMethod)

            val title = recordSession.descriptor.title
            val artist = recordSession.descriptor.artist
            val album = recordSession.descriptor.album
            val durationMs = recordSession.descriptor.durationMs
            val cacheKey = "$ownerPackage|$currentGen"
            if (realLyricsByPackage[cacheKey].isNullOrBlank() && !title.isNullOrBlank()) {
                triggerBackgroundFetch(ownerPackage, title, artist.orEmpty(), album, durationMs, currentGen)
            }
        }

        val selected = snapshot.selected
        if (selected != null) {
            if (lastSelectedInstanceId != selected.descriptor.sessionInstanceId) {
                UniversalDiagnostics.sessionSelected(selected, snapshot.selectionRevision)
                lastSelectedInstanceId = selected.descriptor.sessionInstanceId
                lastGeneration = selected.descriptor.trackGeneration
            } else if (lastGeneration != selected.descriptor.trackGeneration) {
                UniversalDiagnostics.trackChanged(selected)
                lastGeneration = selected.descriptor.trackGeneration
            }
        }
    }

    private fun enrichMetadataBeforeSet(
        record: Any,
        ownerPackage: String,
        metadata: MediaMetadata,
        metadataDescription: String?,
        userField: Field?,
        playbackField: Field?,
        contextField: Field?
    ): MediaMetadata? {
        rememberArtwork(ownerPackage, metadata)
        val playback = playbackField?.get(record) as? PlaybackState
        val state = playback?.state
        val observation = SessionObservation(
            userId = (userField?.get(record) as? Int) ?: 0,
            ownerPackage = ownerPackage,
            sessionInstanceId = System.identityHashCode(record).toString(16),
            raw = RawSessionMetadata(
                mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                displaySubtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ,playbackQueueItemId = playback?.activeQueueItemId?.takeIf { it >= 0L }
                ,playbackExtrasMediaId = playbackExtrasMediaId(playback)
                ,mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ,positionMs = playback?.position
            ),
            playbackState = state,
            playing = PlaybackStates.isPlaying(state),
            observedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        repository.setNotificationAccessGranted(true)
        repository.onListenerConnected()
        repository.upsert(observation, boundPackages)
        val snapshot = repository.snapshot()
        val formatted = format(snapshot.selected, snapshot.sessions.size, snapshot.selectionRevision)
        latestSnapshotText = formatted

        val context = (contextField?.get(record) as? Context) ?: currentSystemContext()
        if (context != null) {
            ensureRequestReceiver(context)
            sendSnapshotBroadcast(context, formatted)
        }

        val recordSession = repository.get(observation.sessionInstanceId) ?: return null
        UniversalDiagnostics.metadataObserved(recordSession, "before-setMetadata")
        val currentGen = recordSession.descriptor.trackGeneration

        realLyricsByPackage.keys.filter { it.startsWith("$ownerPackage|") && it != "$ownerPackage|$currentGen" }.forEach {
            realLyricsByPackage.remove(it)
        }

        // During Salt cold start the resolver can temporarily reject the
        // stored title as Bluetooth lyric churn even though this incoming
        // metadata already contains the clean track identity. Use it as a
        // guarded lookup fallback so the first song does not require a skip.
        val descriptionParts = metadataDescription?.split(",").orEmpty().map { it.trim() }
        val descriptionTitle = descriptionParts.firstOrNull()
            ?.takeIf { it.isNotEmpty() && !BluetoothLyricDemux.looksLikeLyricTitle(it) }
        val descriptionArtist = descriptionParts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val incomingTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.trim()?.takeIf { it.isNotEmpty() && !BluetoothLyricDemux.looksLikeLyricTitle(it) }
        val incomingArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val recovered = if (recordSession.descriptor.title == null && incomingTitle == null) {
            recoverIdentityFromCache(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                recordSession.descriptor.artist ?: incomingArtist,
                recordSession.descriptor.album ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                recordSession.descriptor.durationMs
            )
        } else null
        val title = recordSession.descriptor.title ?: incomingTitle ?: recovered?.first ?: descriptionTitle
        val artist = recordSession.descriptor.artist ?: incomingArtist ?: descriptionArtist
        val album = recordSession.descriptor.album
        val durationMs = recordSession.descriptor.durationMs
        val cacheKey = "$ownerPackage|$currentGen"

        var realJson = realLyricsByPackage[cacheKey]
        if (realJson.isNullOrBlank() && !title.isNullOrBlank()) {
            val manualKey = bindingKey(title, artist.orEmpty())
            val manual = loadManualBinding(manualKey)
            if (!manual.isNullOrBlank()) {
                realJson = updateGeneration(manual, currentGen)
                realLyricsByPackage[cacheKey] = realJson
            } else {
                val cached = loadCachedLyric(title, artist.orEmpty())
                if (!cached.isNullOrBlank()) {
                    realJson = updateGeneration(cached, currentGen)
                    realLyricsByPackage[cacheKey] = realJson
                } else {
                    triggerBackgroundFetch(ownerPackage, title, artist.orEmpty(), album, durationMs, currentGen)
                }
            }
        }

        if (!realJson.isNullOrBlank()) {
            UniversalDiagnostics.lyricInfoAttached(ownerPackage, currentGen, "metadata-rewrite")
            return MediaMetadata.Builder(metadata).putString("lyricInfo", realJson).build()
        }

        return null
    }

    private fun overlayMetadata(
        record: Any,
        session: ResolvedSession,
        metadataField: Field?,
        lockField: Field?,
        pushMetadataUpdateMethod: Method?,
        force: Boolean = false
    ) {
        if (isOverriding.get() == true) return
        val ownerPackage = session.descriptor.ownerPackage
        if (!boundPackages.contains(ownerPackage)) {
            return
        }
        val gen = session.descriptor.trackGeneration
        val realJson = realLyricsByPackage["$ownerPackage|$gen"]
        if (realJson.isNullOrBlank()) {
            return
        }
        val originalMetadata = metadataField?.get(record) as? MediaMetadata ?: return
        val existing = originalMetadata.getString("lyricInfo")
        val genMarker = "\"sessionGeneration\":$gen"
        if (!force && existing != null && existing.contains("[CLLUniversal]") && existing.contains(genMarker) && (realJson == null || existing == realJson)) {
            return
        }
        if (UniversalMetadataOverlayPolicy.shouldPreserveExisting(existing, force)) {
            return
        }

        try {
            isOverriding.set(true)
            val builder = MediaMetadata.Builder(originalMetadata)
            builder.putString("lyricInfo", realJson)
            val newMetadata = builder.build()

            val lock = lockField?.get(record) ?: record
            synchronized(lock) {
                metadataField?.set(record, newMetadata)
            }

            pushMetadataUpdateMethod?.invoke(record)

            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = UniversalDiagnostics.COMPONENT,
                    area = "overlay",
                    event = "LYRIC_OVERLAY_INJECTED",
                    session = session.descriptor.sessionInstanceId,
                    generation = session.descriptor.trackGeneration,
                    reason = "bytes=${realJson.length}"
                )
            )
        } catch (t: Throwable) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = UniversalDiagnostics.COMPONENT,
                    area = "overlay",
                    event = "LYRIC_OVERLAY_FAILED",
                    session = session.descriptor.sessionInstanceId,
                    generation = session.descriptor.trackGeneration,
                    reason = t.message
                )
            )
        } finally {
            isOverriding.set(false)
        }
    }


    private fun ensureRequestReceiver(context: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(UniversalSnapshotStore.ACTION_REQUEST_SNAPSHOT)
                    addAction(UniversalSnapshotStore.ACTION_UPDATE_PLAYER_BINDINGS)
                    addAction(UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC)
                    addAction(UniversalSnapshotStore.ACTION_UPDATE_SOURCE_CONFIG)
                    addAction(UniversalSnapshotStore.ACTION_CLEAR_CACHE)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        when (intent?.action) {
                            UniversalSnapshotStore.ACTION_REQUEST_SNAPSHOT -> {
                                val current = latestSnapshotText ?: format(null, 0, 0)
                                latestSnapshotText = upsertCachedSongs(current)
                                sendSnapshotBroadcast(context, latestSnapshotText ?: current, force = true)
                            }
                            UniversalSnapshotStore.ACTION_UPDATE_PLAYER_BINDINGS -> {
                                val list = intent.getStringArrayListExtra(UniversalSnapshotStore.EXTRA_BOUND_PACKAGES)
                                if (list != null) {
                                    boundPackages = PlayerBindingPolicy.sanitize(list.toSet())
                                    persistBoundPackages(boundPackages)
                                    repository.updateBoundFilter(boundPackages, SystemClock.elapsedRealtime())
                                    UniversalBridgeBindingNotifier.notify(context, boundPackages)
                                    val snap = repository.snapshot()
                                    latestSnapshotText = format(snap.selected, snap.sessions.size, snap.selectionRevision)
                                    sendSnapshotBroadcast(context, latestSnapshotText ?: "")
                                    StructuredDiagnostics.logInfo(
                                        DiagnosticEvent(
                                            component = UniversalDiagnostics.COMPONENT,
                                            area = "bindings",
                                            event = "PLAYER_BINDINGS_UPDATED",
                                            reason = "count=${boundPackages.size}"
                                        )
                                    )
                                } else {
                                    StructuredDiagnostics.logWarning(
                                        DiagnosticEvent(
                                            component = UniversalDiagnostics.COMPONENT,
                                            area = "bindings",
                                            event = "PLAYER_BINDINGS_UPDATE_EMPTY",
                                            reason = "missing extra_bound_packages"
                                        )
                                    )
                                }
                            }
                            UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC -> {
                                val pkg = intent.getStringExtra(UniversalSnapshotStore.EXTRA_OWNER_PACKAGE) ?: return
                                val gen = intent.getLongExtra(UniversalSnapshotStore.EXTRA_GENERATION, -1L)
                                val lyricInfo = intent.getStringExtra(UniversalSnapshotStore.EXTRA_LYRIC_INFO) ?: return
                                val isManual = intent.getBooleanExtra("extra_is_manual", false)
                                val trackKey = intent.getStringExtra("extra_track_key")
                                if (isManual && !trackKey.isNullOrBlank()) {
                                    saveManualBinding(trackKey, lyricInfo)
                                    val payload = runCatching { JSONObject(lyricInfo) }.getOrNull()
                                    saveCachedLyric(
                                        payload?.optString("songName").orEmpty().ifBlank { trackKey.substringBefore("|") },
                                        payload?.optString("artist").orEmpty().ifBlank { trackKey.substringAfter("|", "") },
                                        lyricInfo
                                    )
                                }
                                val cacheKey = if (gen > 0) "$pkg|$gen" else pkg
                                realLyricsByPackage[cacheKey] = lyricInfo
                                val activeRecord = activeRecords[pkg]
                                val activeSession = repository.snapshot().sessions.find { it.descriptor.ownerPackage == pkg }
                                if (activeRecord != null && activeSession != null && (gen <= 0 || activeSession.descriptor.trackGeneration == gen)) {
                                    overlayMetadata(activeRecord, activeSession, cachedMetadataField, cachedLockField, cachedPushMetadataUpdateMethod, force = true)
                                }
                            }
                            UniversalSnapshotStore.ACTION_UPDATE_SOURCE_CONFIG -> {
                                val encoded = intent.getStringExtra(UniversalSnapshotStore.EXTRA_SOURCE_PRIORITY) ?: return
                                val priority = LyricsSourceConfigStore.decode(encoded).mapNotNull {
                                    runCatching { io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId.valueOf(it) }.getOrNull()
                                }
                                val wordTiming = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_WORD_TIMING_ENABLED, wordTimingEnabled)
                                val translation = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_TRANSLATION_ENABLED, translationEnabled)
                                val rawLyric = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_RAW_LYRIC_ENABLED, rawLyricEnabled)
                                val debug = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_DEBUG_ENABLED, debugEnabled)
                                val priorityChanged = priority != sourcePriority
                                val wordTimingChanged = wordTiming != wordTimingEnabled
                                val translationChanged = translation != translationEnabled
                                val rawLyricChanged = rawLyric != rawLyricEnabled
                                val debugChanged = debug != debugEnabled
                                sourcePriority = priority
                                wordTimingEnabled = wordTiming
                                translationEnabled = translation
                                rawLyricEnabled = rawLyric
                                debugEnabled = debug
                                // Only a processing transition may rewrite the in-memory payloads.
                                if ((wordTimingChanged && !wordTiming) || (rawLyricChanged && !rawLyric)) {
                                    realLyricsByPackage.replaceAll { _, value -> stripRawLyric(value) }
                                }
                                if (translationChanged && !translation) {
                                    realLyricsByPackage.replaceAll { _, value -> stripTranslation(value) }
                                }
                                if (debugChanged) {
                                    StructuredDiagnostics.configure(debugEnabled = debug)
                                }
                                configExecutor.execute {
                                    if (priorityChanged) persistSourceConfig(priority)
                                    if (wordTimingChanged) persistWordTimingEnabled(wordTiming)
                                    if (translationChanged) persistTranslationEnabled(translation)
                                    if (rawLyricChanged) persistRawLyricEnabled(rawLyric)
                                    if (debugChanged) persistDebugEnabled(debug)
                                }
                                UniversalDiagnostics.sourceConfigUpdated(sourcePriority.map { it.name })
                            }
                            UniversalSnapshotStore.ACTION_CLEAR_CACHE -> {
                                val cleared = UniversalLyricCache.clear()
                                realLyricsByPackage.clear()
                                val ctx = currentSystemContext()
                                val snap = latestSnapshotText ?: format(null, 0, 0)
                                latestSnapshotText = upsertCachedSongs(snap)
                                if (ctx != null) {
                                    sendSnapshotBroadcast(ctx, latestSnapshotText ?: snap, cacheCleared = cleared.songs)
                                }
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
                UniversalDiagnostics.commandReceiverRegistered(true)
                requestPlayerBindingsSync(context)
            } catch (error: Throwable) {
                UniversalDiagnostics.commandReceiverRegistered(false, error.javaClass.simpleName)
            }
        }
    }

    private fun requestPlayerBindingsSync(context: Context) {
        val intent = Intent(UniversalSnapshotStore.ACTION_REQUEST_PLAYER_BINDINGS_SYNC).apply {
            setPackage("io.github.andrealtb.coloroslyrics.provider.universal")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            addFlags(0x01000000)
        }
        val sent = UniversalBridgeBindingNotifier.sendSafely(context, intent)
        UniversalDiagnostics.bridgeBindingsNotified(
            boundPackages.size,
            "provider-bindings-request",
            sent.isSuccess,
            sent.exceptionOrNull()?.javaClass?.simpleName
        )
    }

    private fun sendSnapshotBroadcast(context: Context, text: String, cacheCleared: Int? = null, force: Boolean = false) {
        runCatching {
            val owner = text.lineSequence().firstOrNull { it.startsWith("package=") }?.substringAfter('=')
            val artwork = owner?.let(artworkByPackage::get)
            val artworkRevision = artwork?.revision ?: 0L
            if (!force && cacheCleared == null && text == lastBroadcastSnapshotText &&
                artworkRevision == lastBroadcastArtworkRevision) {
                return@runCatching
            }
            val intent = Intent(UniversalSnapshotStore.ACTION_SNAPSHOT_UPDATE).apply {
                setPackage("io.github.andrealtb.coloroslyrics.provider.universal")
                putExtra(UniversalSnapshotStore.EXTRA_SNAPSHOT, text)
                val count = text.lineSequence().firstOrNull { it.startsWith("cachedSongs=") }
                    ?.substringAfter('=')?.toIntOrNull() ?: 0
                putExtra(UniversalSnapshotStore.EXTRA_CACHED_SONGS, count)
                artwork?.let { putExtra(UniversalSnapshotStore.EXTRA_ARTWORK, it.bitmap) }
                putExtra(UniversalSnapshotStore.EXTRA_ARTWORK_REVISION, artworkRevision)
                if (cacheCleared != null) {
                    putExtra(UniversalSnapshotStore.EXTRA_CACHE_CLEARED, cacheCleared)
                }
                addFlags(0x00000020)
            }
            val allUser = runCatching {
                Class.forName("android.os.UserHandle").getDeclaredField("ALL").get(null) as? UserHandle
            }.getOrNull()
            if (allUser != null) {
                context.sendBroadcastAsUser(intent, allUser)
            } else {
                context.sendBroadcast(intent)
            }
            lastBroadcastSnapshotText = text
            lastBroadcastArtworkRevision = artworkRevision
        }
    }

    private fun rememberArtwork(ownerPackage: String, metadata: MediaMetadata) {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: run {
                artworkByPackage.remove(ownerPackage)
                return
            }
        artworkByPackage.compute(ownerPackage) { _, previous ->
            if (previous != null && previous.source.get() === bitmap &&
                previous.sourceGenerationId == bitmap.generationId) {
                return@compute previous
            }
            val longest = maxOf(bitmap.width, bitmap.height)
            val preview = if (longest > 256) {
                val scale = 256f / longest.toFloat()
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            // Re-parcelled metadata can carry identical pixels in a new Bitmap object.
            // Compare only the bounded preview here, never in the app's UI callback.
            if (previous != null && runCatching { previous.bitmap.sameAs(preview) }.getOrDefault(false)) {
                return@compute previous.copy(
                    source = java.lang.ref.WeakReference(bitmap),
                    sourceGenerationId = bitmap.generationId
                )
            }
            ArtworkSnapshot(
                java.lang.ref.WeakReference(bitmap),
                bitmap.generationId,
                preview,
                nextArtworkRevision.incrementAndGet()
            )
        }
    }

    private fun loadBoundPackages(): Set<String> {
        val appBindings = readBindingFile(APP_BINDINGS_FILE_PATH)
        if (appBindings != null) {
            persistBoundPackages(appBindings)
            return appBindings
        }
        val systemBindings = readBindingFile(BINDINGS_FILE_PATH)
        if (!systemBindings.isNullOrEmpty()) {
            return systemBindings
        }
        return PlayerBindingPolicy.sanitize(DEFAULT_BOUND_PACKAGES)
    }

    private fun readBindingFile(path: String): Set<String>? {
        val file = java.io.File(path)
        if (!file.isFile) return null
        return runCatching {
            val array = org.json.JSONArray(file.readText())
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
            PlayerBindingPolicy.sanitize(set)
        }.getOrNull()
    }

    private fun refreshBindingsFromDisk(): Boolean {
        val loaded = loadBoundPackages()
        if (loaded == boundPackages) return false
        boundPackages = loaded
        persistBoundPackages(loaded)
        repository.updateBoundFilter(loaded, SystemClock.elapsedRealtime())
        currentSystemContext()?.let { UniversalBridgeBindingNotifier.notify(it, loaded) }
        UniversalDiagnostics.playerBindingsReloaded(loaded.size, "disk")
        return true
    }

    private fun persistBoundPackages(packages: Set<String>) {
        runCatching {
            val array = org.json.JSONArray(PlayerBindingPolicy.sanitize(packages))
            java.io.File(BINDINGS_FILE_PATH).writeText(array.toString())
        }
    }

    private fun currentSystemContext(): Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)
            activityThreadClass.getDeclaredMethod("getSystemContext").invoke(currentActivityThread) as? Context
        }.getOrNull()
    }

    private fun format(selected: ResolvedSession?, sessionCount: Int, selectionRevision: Long): String {
        val descriptor = selected?.descriptor
        val isBound = boundPackages.contains(descriptor?.ownerPackage)
        val lyricPayload = descriptor?.let {
            realLyricsByPackage["${it.ownerPackage}|${it.trackGeneration}"] ?: realLyricsByPackage[it.ownerPackage]
        }?.let { runCatching { JSONObject(it) }.getOrNull() }
        return buildString {
            append("source=system_server\n")
            append("writeBack=true network=false\n")
            append("boundPlayer=").append(isBound).append('\n')
            append("boundCount=").append(boundPackages.size).append('\n')
            append("selectionRevision=").append(selectionRevision).append('\n')
            append("sessions=").append(sessionCount).append('\n')
            append("package=").append(descriptor?.ownerPackage ?: "(none)").append('\n')
            append("title=").append(descriptor?.title ?: "(missing)").append('\n')
            append("artist=").append(descriptor?.artist ?: "(missing)").append('\n')
            append("album=").append(descriptor?.album ?: "(missing)").append('\n')
            append("durationMs=").append(descriptor?.durationMs?.toString() ?: "unknown").append('\n')
            append("generation=").append(descriptor?.trackGeneration ?: 0).append('\n')
            append("titlePolluted=").append(descriptor?.titlePolluted ?: false).append('\n')
            append("rawTitle=").append(descriptor?.rawTitle ?: "(missing)").append('\n')
            append("demux=").append(selected?.demuxSource ?: "none").append('\n')
            append("playbackState=").append(descriptor?.playbackState ?: -1).append('\n')
            append("lyricSource=").append(lyricPayload?.optString("source").orEmpty().ifBlank { "none" }).append('\n')
            append("lyricStatus=").append(
                when {
                    lyricPayload == null -> "pending"
                    lyricPayload.optBoolean("noLyric") -> "noLyric"
                    lyricPayload.optString("lyric").isNotBlank() -> "success"
                    else -> "failed"
                }
            ).append('\n')
            append("cachedSongs=").append(UniversalLyricCache.getCacheCount()).append('\n')
        }
    }

    private fun fieldOrNull(type: Class<*>, name: String): Field? =
        runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun triggerBackgroundFetch(
        ownerPackage: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        generation: Long
    ) {
        // Bluetooth projection changes TITLE every few seconds. Fetches are
        // keyed by the resolved session generation instead of display text,
        // with a short retry backoff for an empty/failed response.
        val fetchKey = "$ownerPackage|$generation"
        val now = SystemClock.elapsedRealtime()
        val previousBackoff = fetchBackoffUntil[fetchKey] ?: 0L
        if (now < previousBackoff) return
        fetchBackoffUntil[fetchKey] = now + 5_000L

        backgroundExecutor.execute {
            runCatching {
                android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
                val manualKey = bindingKey(title, artist)
                val manual = loadManualBinding(manualKey)
                if (!manual.isNullOrBlank()) {
                    val negative = runCatching { JSONObject(manual).optBoolean("noLyric", false) }.getOrDefault(false)
                    UniversalDiagnostics.cacheLookup("manual", generation, hit = true, noLyric = negative)
                    UniversalDiagnostics.fetchStarted(ownerPackage, generation, "manual-cache")
                    if (negative) {
                        return@execute
                    }
                    val updated = updateGeneration(manual, generation)
                    applyFetchedLyric(ownerPackage, generation, updated)
                    fetchBackoffUntil.remove(fetchKey)
                    UniversalDiagnostics.fetchFinished(ownerPackage, generation, "manual-cache", updated.length)
                    return@execute
                }
                UniversalDiagnostics.cacheLookup("manual", generation, hit = false, noLyric = false)

                val cached = loadCachedLyric(title, artist)
                if (!cached.isNullOrBlank()) {
                    val negative = runCatching { JSONObject(cached).optBoolean("noLyric", false) }.getOrDefault(false)
                    UniversalDiagnostics.cacheLookup("auto", generation, hit = true, noLyric = negative)
                    UniversalDiagnostics.fetchStarted(ownerPackage, generation, "auto-cache")
                    if (negative) {
                        return@execute
                    }
                    val updated = updateGeneration(cached, generation)
                    applyFetchedLyric(ownerPackage, generation, updated)
                    fetchBackoffUntil.remove(fetchKey)
                    UniversalDiagnostics.fetchFinished(ownerPackage, generation, "auto-cache", updated.length)
                    return@execute
                }
                UniversalDiagnostics.cacheLookup("auto", generation, hit = false, noLyric = false)

                UniversalDiagnostics.fetchStarted(
                    ownerPackage,
                    generation,
                    "network priority=" + sourcePriority.joinToString(",") { it.name } +
                        " titleHash=" + DiagnosticHasher.sha256(title).take(12) +
                        " artistHash=" + DiagnosticHasher.sha256(artist).take(12) +
                        " durationMs=" + (durationMs?.toString() ?: "unknown") +
                        " wordTiming=" + wordTimingEnabled
                )
                val query = io.github.andrealtb.coloroslyrics.provider.universal.api.TrackQuery(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    generation = generation
                )
                val report = aggregator.fetchFirst(query, sourcePriority)
                val result = report.result
                val instrumental = result != null && io.github.andrealtb.coloroslyrics.provider.universal.api.SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)
                val usable = result != null && result.lyric.isNotBlank() && !instrumental
                val outcome = NoLyricPolicy.decide(
                    hasUsableLyric = usable,
                    instrumental = instrumental,
                    transientFailure = report.transientFailure && !usable
                )
                if (outcome == NoLyricPolicy.Outcome.PUBLISH_LYRICS && result != null) {
                    val plainLyric = toPlainLrc(result.lyric)
                    val alignedTranslation = run {
                        val primary = io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.parseLrc(plainLyric)
                        val trans = io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.parseLrc(result.translationLyric)
                        io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.toTranslationLrc(
                            io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.merge(primary, trans)
                        ).takeIf { it.isNotBlank() }
                    }
                    val attachRaw = wordTimingEnabled && rawLyricEnabled && hasAttachableRawLyric(result.rawLyric, plainLyric)
                    val json = JSONObject().apply {
                        put("songName", result.title)
                        put("artist", result.artist)
                        put("album", album.orEmpty())
                        put("durationMs", durationMs ?: 0L)
                        put("songId", "universal-$generation")
                        put("lyricType", 0)
                        put("id", "")
                        put("noLyric", false)
                        put("lyric", plainLyric)
                        if (attachRaw) {
                            put("rawLyric", result.rawLyric)
                        }
                        if (translationEnabled && !alignedTranslation.isNullOrBlank()) {
                            put("transLyric", alignedTranslation)
                            put("translationLyric", alignedTranslation)
                            put("translationLanguageTag", java.util.Locale.getDefault().toLanguageTag())
                        }
                        put("provider", "io.github.andrealtb.coloroslyrics.provider.universal")
                        put("source", result.source)
                        put("sessionGeneration", generation)
                        put("remark", "[CLLUniversal]")
                    }.toString()

                    saveCachedLyric(title, artist, json)
                    applyFetchedLyric(ownerPackage, generation, json)
                    fetchBackoffUntil.remove(fetchKey)
                    UniversalDiagnostics.fetchFinished(ownerPackage, generation, result.source, json.length)
                    UniversalDiagnostics.lyricInfoComposed(
                        ownerPackage = ownerPackage,
                        generation = generation,
                        source = result.source,
                        lyricChars = result.lyric.length,
                        rawLyricAttached = attachRaw,
                        translationAttached = translationEnabled && !alignedTranslation.isNullOrBlank(),
                        wordTimingEnabled = wordTimingEnabled
                    )
                } else {
                    val rejectReason = when {
                        report.transientFailure -> "transient:" + report.reason
                        instrumental -> "instrumentalOrCreditsOnly source=" + (result?.source ?: "none")
                        else -> "miss:" + report.reason
                    }
                    UniversalDiagnostics.fetchEmpty(ownerPackage, generation, rejectReason)
                    if (outcome == NoLyricPolicy.Outcome.AUTO_NEGATIVE) {
                        val reason = NoLyricPolicy.autoReason(instrumental)
                        val noLyricJson = NoLyricPolicy.payloadJson(title, artist, reason)
                        saveCachedLyric(title, artist, noLyricJson)
                        applyFetchedLyric(ownerPackage, generation, noLyricJson)
                    } else {
                        fetchBackoffUntil.remove(fetchKey)
                    }
                }
            }.onFailure { error ->
                UniversalDiagnostics.fetchEmpty(
                    ownerPackage,
                    generation,
                    "exception=${error.javaClass.simpleName}"
                )
                fetchBackoffUntil.remove(fetchKey)
            }
        }
    }

    private fun playbackExtrasMediaId(state: PlaybackState?): String? {
        val extras = state?.extras ?: return null
        val keys = arrayOf(
            "androidx.media.PlaybackStateCompat.Extras.KEY_MEDIA_ID",
            "android.media.playback.extra.MEDIA_ID",
            "media_id"
        )
        return keys.firstNotNullOfOrNull { key ->
            extras.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun updateGeneration(jsonStr: String, generation: Long): String {
        return runCatching {
            val json = JSONObject(jsonStr)
            json.put("sessionGeneration", generation)
            json.put("songId", "universal-$generation")
            applyWordTimingPolicy(json).toString()
        }.getOrDefault(jsonStr)
    }

    private fun applyFetchedLyric(ownerPackage: String, generation: Long, json: String) {
        realLyricsByPackage["$ownerPackage|$generation"] = applyWordTimingPolicy(JSONObject(json)).toString()
        replayMetadataForGeneration(ownerPackage, generation)
        // The first callback can race SystemUI's notification/card creation.
        // Replay after the normal load window as a fail-open compatibility
        // measure for ColorOS builds that ignore pushMetadataUpdate early.
        listOf(250L, 750L, 1500L).forEach { delayMs ->
            metadataReplayExecutor.schedule(
                { replayMetadataForGeneration(ownerPackage, generation) },
                delayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
        }
    }

    private fun replayMetadataForGeneration(ownerPackage: String, generation: Long) {
        val record = activeRecords[ownerPackage] ?: return
        val session = repository.snapshot().sessions.firstOrNull {
            it.descriptor.ownerPackage == ownerPackage && it.descriptor.trackGeneration == generation
        } ?: return
        if (realLyricsByPackage["$ownerPackage|$generation"].isNullOrBlank()) return
        overlayMetadata(
            record,
            session,
            cachedMetadataField,
            cachedLockField,
            cachedPushMetadataUpdateMethod,
            force = true
        )
    }

    private fun loadCachedLyric(key: String): String? {
        val file = java.io.File("/data/system/universal_lyric_cache.json")
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText()).optString(key).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun loadSourceConfig(): String = runCatching {
        java.io.File("/data/system/universal_source_config.json").takeIf { it.isFile }?.readText()
    }.getOrNull() ?: LyricsSourceConfigStore.encode(listOf("QQ", "NETEASE", "APPLE_MUSIC"))

    private fun persistSourceConfig(priority: List<io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId>) {
        runCatching { java.io.File("/data/system/universal_source_config.json").writeText(LyricsSourceConfigStore.encode(priority.map { it.name })) }
    }

    private fun loadWordTimingEnabled(): Boolean = runCatching {
        java.io.File("/data/system/universal_word_timing.enabled").readText().trim() == "1"
    }.getOrDefault(false)

    private fun persistWordTimingEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/universal_word_timing.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadRawLyricEnabled(): Boolean = runCatching {
        java.io.File("/data/system/universal_raw_lyric.enabled").readText().trim() != "0"
    }.getOrDefault(true)

    private fun persistRawLyricEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/universal_raw_lyric.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadDebugEnabled(): Boolean = runCatching {
        java.io.File("/data/system/universal_debug.enabled").readText().trim() == "1"
    }.getOrDefault(false)

    private fun persistDebugEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/universal_debug.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadTranslationEnabled(): Boolean = runCatching {
        java.io.File("/data/system/universal_translation.enabled").readText().trim() != "0"
    }.getOrDefault(true)

    private fun persistTranslationEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/universal_translation.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun toPlainLrc(value: String): String =
        value.replace(Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>"), "")

    private fun bindingKey(title: String, artist: String): String =
        "${title.trim()}|${artist.trim()}".lowercase()

    private fun applyWordTimingPolicy(json: JSONObject): JSONObject {
        val raw = json.optString("rawLyric")
        val hadRaw = raw.isNotBlank()
        val hadWordTiming = hasInlineWordTiming(raw)
        val plain = json.optString("lyric")
        val attached = wordTimingEnabled && rawLyricEnabled && hasAttachableRawLyric(raw, plain)
        if (plain.isNotBlank()) json.put("lyric", toPlainLrc(plain))
        if (attached) json.put("rawLyric", raw) else json.remove("rawLyric")
        if (!translationEnabled) {
            json.remove("transLyric")
            json.remove("translationLyric")
            json.remove("translationLanguageTag")
        }
        UniversalDiagnostics.rawLyricPolicy(
            generation = json.optLong("sessionGeneration"),
            enabled = wordTimingEnabled,
            hadRaw = hadRaw,
            hadWordTiming = hadWordTiming,
            attached = attached
        )
        return json
    }

    private fun hasInlineWordTiming(value: String?): Boolean =
        !value.isNullOrBlank() && Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>").containsMatchIn(value)

    private fun hasTimedLrc(value: String?): Boolean =
        !value.isNullOrBlank() && Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]").containsMatchIn(value)

    private fun hasAttachableRawLyric(raw: String?, plain: String?): Boolean =
        hasInlineWordTiming(raw) || hasTimedLrc(raw) || hasTimedLrc(plain)

    private fun stripRawLyric(value: String): String = runCatching {
        JSONObject(value).apply { remove("rawLyric") }.toString()
    }.getOrDefault(value)

    private fun stripTranslation(value: String): String = runCatching {
        JSONObject(value).apply {
            remove("transLyric")
            remove("translationLyric")
            remove("translationLanguageTag")
        }.toString()
    }.getOrDefault(value)

    private fun recoverIdentityFromCache(
        rawTitle: String?,
        artist: String?,
        album: String?,
        durationMs: Long?
    ): Pair<String, String>? {
        val needle = normalizeLyricText(rawTitle)
        if (needle.length < 8) return null
        val file = java.io.File("/data/system/universal_lyric_cache.json")
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val matches = mutableListOf<Pair<String, String>>()
            root.keys().forEach { key ->
                if (UniversalLyricCache.identityFromKey(key) == null) return@forEach
                val payload = runCatching { JSONObject(root.optString(key)) }.getOrNull() ?: return@forEach
                if (payload.optBoolean("noLyric", false)) return@forEach
                val candidateArtist = payload.optString("artist")
                val candidateAlbum = payload.optString("album")
                if (!sameIdentityText(artist, candidateArtist)) return@forEach
                if (!album.isNullOrBlank() && candidateAlbum.isNotBlank() && !sameIdentityText(album, candidateAlbum)) return@forEach
                val candidateDuration = payload.optLong("durationMs").takeIf { it > 0L }
                if (durationMs != null && candidateDuration != null && kotlin.math.abs(durationMs - candidateDuration) > 2_500L) return@forEach
                val lyricText = normalizeLyricText(payload.optString("lyric"))
                if (lyricText.contains(needle)) {
                    val songName = payload.optString("songName")
                    if (songName.isNotBlank()) matches += songName to candidateArtist
                }
            }
            matches.distinct().singleOrNull()
        }.getOrNull()
    }

    private fun normalizeLyricText(value: String?): String = value.orEmpty().lowercase()
        .replace(Regex("[\\[<]\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?[\\]>]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun sameIdentityText(left: String?, right: String?): Boolean =
        left?.trim()?.equals(right?.trim(), ignoreCase = true) == true

    private fun saveCachedLyric(title: String, artist: String, jsonStr: String) {
        UniversalLyricCache.put(title, artist, jsonStr)
        publishCacheCount()
    }

    private fun loadCachedLyric(title: String, artist: String): String? =
        UniversalLyricCache.get(title, artist)

    private fun publishCacheCount() {
        val ctx = currentSystemContext() ?: return
        val snap = latestSnapshotText ?: return
        latestSnapshotText = upsertCachedSongs(snap)
        sendSnapshotBroadcast(ctx, latestSnapshotText ?: snap)
    }

    private fun upsertCachedSongs(text: String): String {
        val count = UniversalLyricCache.getCacheCount()
        val lines = text.lines().filterNot { it.startsWith("cachedSongs=") }
        return (lines + ("cachedSongs=" + count)).joinToString("\n").trimEnd() + "\n"
    }

    private fun loadManualBinding(key: String): String? {
        val file = java.io.File("/data/system/universal_manual_bindings.json")
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText()).optString(key).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun saveManualBinding(key: String, jsonStr: String) {
        runCatching {
            val file = java.io.File("/data/system/universal_manual_bindings.json")
            val obj = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            obj.put(key, jsonStr)
            file.writeText(obj.toString())
        }
    }

    private fun clearCache(): Int {
        return UniversalLyricCache.clear().songs
    }
}
