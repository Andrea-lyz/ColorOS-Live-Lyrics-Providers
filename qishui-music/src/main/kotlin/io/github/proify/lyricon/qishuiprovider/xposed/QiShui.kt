package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.extensions.android.copy
import io.github.proify.extensions.json
import io.github.proify.extensions.md5
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import io.github.proify.lyricon.qishuiprovider.xposed.parser.toRichLyric
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.concurrent.Executors

object QiShui : YukiBaseHooker() {

    private const val SONG_CACHE_MAX_ENTRIES = 32
    private const val OFFICIAL_SONG_CACHE_MAX_ENTRIES = 32
    private const val MISSING_SONG_CACHE_MAX_ENTRIES = 32
    private const val RESOLUTION_LOG_THROTTLE_MS = 10_000L
    private const val ACTION_TOGGLE_TRANSLATION =
        "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
    private const val TRANSLATION_ACTION_NAME = "翻译"
    private const val NET_CACHE_DIAG_THROTTLE_MS = 10_000L
    private const val NET_CACHE_RECURSIVE_MAX_FILES = 1_000
    private var provider: LyriconProvider? = null

    private var curMediaId: String? = null
    private var currentTrackGeneration = 0L
    private var lastSong: Song? = null
    private var lastSongGeneration = 0L
    private var translationActionInjectionLogged = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val resolutionExecutor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "QiShui-LyricResolver").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
    private var resolutionToken = 0L
    private var pendingLyricResolution: PendingLyricResolution? = null
    private var authoritativeSession = WeakReference<MediaSession>(null)
    @Volatile
    private var officialTrackAuthority: QiShuiTrackAuthority? = null
    private var latestPlaybackState: PlaybackState? = null
    private var latestPlaybackSnapshot: QiShuiPlaybackSnapshot? = null
    private var lastLoggedPlaybackSnapshot: QiShuiPlaybackSnapshot? = null

    private data class MissingSong(
        val song: Song,
        val expiresAtElapsedMillis: Long
    )

    private data class PendingLyricResolution(
        val mediaId: String,
        val trackGeneration: Long,
        val attempt: Int,
        val token: Long,
        val metadata: Metadata?,
        val inFlight: Boolean
    )

    private sealed interface ResolutionResult {
        data class Found(val song: Song, val source: String) : ResolutionResult
        data object Missing : ResolutionResult
    }

    private val songCache = object : LinkedHashMap<String, Song>(
        SONG_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean {
            return size > SONG_CACHE_MAX_ENTRIES
        }
    }
    private val officialSongCache = object : LinkedHashMap<String, Song>(
        OFFICIAL_SONG_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean {
            return size > OFFICIAL_SONG_CACHE_MAX_ENTRIES
        }
    }
    private val missingSongCache = object : LinkedHashMap<String, MissingSong>(
        MISSING_SONG_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MissingSong>?
        ): Boolean {
            return size > MISSING_SONG_CACHE_MAX_ENTRIES
        }
    }
    private val resolutionLogAtByKey = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 64
        }
    }
    private val netCacheDiagnosticLogAtById = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 32
        }
    }

    override fun onHook() {
        if (!QiShuiPlaybackPolicy.isPlaybackProcess(packageName, processName)) {
            QiShuiLog.debug("event=processSkipped process=$processName expected=$packageName")
            return
        }
        QiShuiLog.debug("event=playbackProcessAccepted process=$processName")

        onAppLifecycle {
            onCreate {
                hook()
            }
        }
    }

    private var hooked = false
    private fun hook() {
        if (hooked) {
            QiShuiLog.debug("event=hookSkipped reason=already-hooked")
            return
        }
        hooked = true

        initProvider()
        hookMediaSession()
        QiShuiOfficialLyrics.install(
            loader = appClassLoader,
            authorityProvider = { officialTrackAuthority },
            onSong = ::acceptOfficialSong
        )
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON),
            processName = processName
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
        QiShuiLog.debug("event=providerRegistered")
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    before {
                        val state = args[0] as? PlaybackState ?: return@before
                        val patched = copyPlaybackStateWithTranslationActionFirst(state)
                        if (patched !== state) {
                            args[0] = patched
                            if (!translationActionInjectionLogged) {
                                translationActionInjectionLogged = true
                                QiShuiLog.debug("event=translationActionInjected")
                            }
                        }
                    }
                    after {
                        val session = instance as? MediaSession ?: return@after
                        val state = args[0] as? PlaybackState ?: return@after
                        runOnMain { handlePlaybackState(session, state) }
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val session = instance as? MediaSession ?: return@after
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        runOnMain { handleMetadata(session, mediaMetadata) }
                    }
                }
            }
    }

    private fun handleMetadata(session: MediaSession, mediaMetadata: MediaMetadata) {
        val id = MetadataCache.resolveId(mediaMetadata)
        if (id.isNullOrBlank()) {
            logResolutionOnce("blank-metadata", "event=metadataIgnored reason=blank-media-id")
            return
        }

        val metadata = MetadataCache.save(mediaMetadata, id)
        val sameTrack = curMediaId == id
        authoritativeSession = WeakReference(session)

        if (!sameTrack) {
            cancelPendingLyricResolution()
            curMediaId = id
            currentTrackGeneration = QiShuiPlaybackPolicy.nextGeneration(
                current = currentTrackGeneration,
                nowElapsedMillis = SystemClock.elapsedRealtime()
            )
            officialTrackAuthority = QiShuiTrackAuthority(id, currentTrackGeneration)
            latestPlaybackState = null
            latestPlaybackSnapshot = null
            lastLoggedPlaybackSnapshot = null
            QiShuiLog.debug(
                "event=trackChanged mediaId=$id generation=$currentTrackGeneration " +
                    "session=${System.identityHashCode(session)}"
            )
            SaltLyricBridge.sendTrackChanged(
                context = appContext,
                mediaId = id,
                title = metadata?.title,
                artist = metadata?.artist,
                duration = metadata?.duration ?: 0L,
                trackGeneration = currentTrackGeneration
            )
        }

        if (sameTrack) {
            val existing = lastSong
            if (existing?.id == id) {
                val enriched = bridgeSongWithCurrentMetadata(existing)
                if (enriched != existing) {
                    commitSong(
                        song = enriched,
                        trackGeneration = currentTrackGeneration,
                        reason = "metadata-enriched"
                    )
                    return
                }
            }
            updateSongIfNeed()
        } else {
            updateSong()
        }
    }

    private fun handlePlaybackState(session: MediaSession, state: PlaybackState) {
        if (authoritativeSession.get() !== session) {
            logResolutionOnce(
                "non-authoritative-session:${System.identityHashCode(session)}",
                "event=playbackIgnored reason=non-authoritative-session " +
                    "session=${System.identityHashCode(session)}"
            )
            return
        }
        publishAuthoritativePlaybackState(session, state, reason = "media-session")
        updateSongIfNeed()
    }

    private fun publishAuthoritativePlaybackState(
        session: MediaSession,
        state: PlaybackState,
        reason: String
    ) {
        val mediaId = curMediaId ?: return
        val trackGeneration = currentTrackGeneration
        if (trackGeneration <= 0L) return

        val snapshot = playbackSnapshot(session, state, trackGeneration)
        latestPlaybackSnapshot = snapshot
        latestPlaybackState = state
        provider?.player?.setPlaybackState(state)
        val metadata = MetadataCache.get(mediaId)
        SaltLyricBridge.sendPlaybackState(
            context = appContext,
            state = state,
            mediaId = mediaId,
            title = metadata?.title,
            artist = metadata?.artist,
            duration = metadata?.duration ?: 0L,
            trackGeneration = trackGeneration
        )
        if (QiShuiPlaybackPolicy.shouldLogPlaybackSnapshot(lastLoggedPlaybackSnapshot, snapshot)) {
            QiShuiLog.debug(
                "event=playbackSnapshot reason=$reason mediaId=$mediaId " +
                "generation=$trackGeneration state=${state.state} position=${state.position} " +
                "session=${System.identityHashCode(session)}"
            )
            lastLoggedPlaybackSnapshot = snapshot
        }
    }

    private fun copyPlaybackStateWithTranslationActionFirst(
        original: PlaybackState
    ): PlaybackState {
        val originalActions = original.customActions.orEmpty()
        if (originalActions.any { it.action == ACTION_TOGGLE_TRANSLATION }) return original

        val translationAction = PlaybackState.CustomAction.Builder(
            ACTION_TOGGLE_TRANSLATION,
            TRANSLATION_ACTION_NAME,
            resolveTranslationActionPlaceholderIcon(original)
        ).build()
        return original.copy(
            customActions = buildList {
                add(translationAction)
                originalActions.filterTo(this) { it.action != ACTION_TOGGLE_TRANSLATION }
            }
        )
    }

    private fun resolveTranslationActionPlaceholderIcon(state: PlaybackState): Int {
        state.customActions.orEmpty().firstOrNull { it.icon != 0 }?.let { return it.icon }
        appContext?.applicationInfo?.icon?.takeIf { it != 0 }?.let { return it }
        return android.R.drawable.ic_menu_manage
    }

    private fun isPlaybackInMotion(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun updateSongIfNeed() {
        val mediaId = curMediaId?.takeIf { it.isNotBlank() } ?: return
        val committed = lastSong
        if (committed?.id != mediaId || committed.lyrics.isNullOrEmpty()) updateSong()
    }

    private fun updateSong() {
        runOnMain(::requestSongResolution)
    }

    private fun requestSongResolution() {
        val id = curMediaId ?: return
        val trackGeneration = currentTrackGeneration
        getCachedOfficialSong(id)?.let { cached ->
            QiShuiLog.debug(
                "event=resolutionCacheHit source=official-memory mediaId=$id " +
                    "lyrics=${cached.lyrics?.size ?: 0}"
            )
            commitSong(cached, trackGeneration, reason = "official-memory-cache")
            clearPendingLyricResolution(id)
            return
        }
        getCachedSong(id)?.let { cached ->
            QiShuiLog.debug(
                "event=resolutionCacheHit source=memory mediaId=$id " +
                    "lyrics=${cached.lyrics?.size ?: 0}"
            )
            commitSong(cached, trackGeneration, reason = "memory-cache")
            clearPendingLyricResolution(id)
            return
        }
        getCachedMissingSong(id)?.let { missing ->
            logResolutionOnce(
                "missing-cache:$id",
                "event=resolutionCacheHit source=negative mediaId=$id"
            )
            if (lastSong?.id != id || lastSongGeneration != trackGeneration) {
                commitSong(
                    song = missing.song,
                    trackGeneration = trackGeneration,
                    rememberMissing = false,
                    reason = "negative-cache"
                )
            }
            return
        }

        val pending = pendingLyricResolution
        if (pending?.mediaId == id && pending.trackGeneration == trackGeneration) return

        val request = PendingLyricResolution(
            mediaId = id,
            trackGeneration = trackGeneration,
            attempt = 0,
            token = nextResolutionToken(),
            metadata = MetadataCache.get(id),
            inFlight = true
        )
        pendingLyricResolution = request
        startSongResolution(request)
    }

    private fun startSongResolution(request: PendingLyricResolution) {
        if (request.attempt == 0) {
            QiShuiLog.debug(
                "event=resolutionStarted mediaId=${request.mediaId} " +
                "generation=${request.trackGeneration} attempt=${request.attempt + 1}/" +
                QiShuiResolutionPolicy.MAX_ATTEMPTS
            )
        }
        resolutionExecutor.execute {
            val result = runCatching { resolveSong(request) }
                .onFailure {
                    QiShuiLog.warningOnce(
                        key = "resolution:${request.mediaId}",
                        message = "event=resolutionFailed mediaId=${request.mediaId}",
                        throwable = it
                    )
                }
                .getOrDefault(ResolutionResult.Missing)
            mainHandler.post { handleResolutionResult(request, result) }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun resolveSong(request: PendingLyricResolution): ResolutionResult {
        val id = request.mediaId
        val cache = runCatching {
            val file = getNetLyricCacheFile(id)
            if (file != null && file.exists()) {
                QiShuiLog.debug("event=netCacheHit mediaId=$id")
                file.inputStream().use {
                    json.decodeFromStream<NetResponseCache>(it)
                }
            } else null
        }.onFailure {
            QiShuiLog.warningOnce(
                key = "net-cache-load:$id",
                message = "event=netCacheLoadFailed mediaId=$id",
                throwable = it
            )
        }.getOrNull()

        if (cache != null) {
            val song = cache.buildSong(id, request.metadata)
            if (!song.lyrics.isNullOrEmpty()) {
                return ResolutionResult.Found(song, "net-cache")
            }
            QiShuiLog.debug("event=netCacheEmpty mediaId=$id")
        } else {
            logResolutionOnce(
                "cache-miss:$id",
                "event=netCacheMiss mediaId=$id"
            )
        }

        val dbHit = PlayableDbCache.findSong(appContext, id, request.metadata)
        if (dbHit != null) {
            QiShuiLog.debug(
                "event=dbCacheHit mediaId=$id db=${dbHit.databaseName} " +
                    "table=${dbHit.tableName}, lyrics=${dbHit.song.lyrics?.size ?: 0}"
            )
            return ResolutionResult.Found(
                dbHit.song,
                "db:${dbHit.databaseName}/${dbHit.tableName}"
            )
        }
        return ResolutionResult.Missing
    }

    private fun handleResolutionResult(
        request: PendingLyricResolution,
        result: ResolutionResult
    ) {
        val pending = pendingLyricResolution
        if (pending == null ||
            pending.token != request.token ||
            pending.mediaId != request.mediaId ||
            pending.trackGeneration != request.trackGeneration ||
            pending.attempt != request.attempt ||
            !pending.inFlight ||
            curMediaId != request.mediaId ||
            currentTrackGeneration != request.trackGeneration
        ) {
            QiShuiLog.debug(
                "event=staleResolutionDropped mediaId=${request.mediaId} " +
                    "generation=${request.trackGeneration} attempt=${request.attempt + 1}"
            )
            return
        }

        when (result) {
            is ResolutionResult.Found -> {
                commitSong(
                    song = result.song,
                    trackGeneration = request.trackGeneration,
                    reason = result.source
                )
                clearPendingLyricResolution(request.mediaId)
            }
            ResolutionResult.Missing -> scheduleNextResolutionAttempt(request)
        }
    }

    private fun scheduleNextResolutionAttempt(request: PendingLyricResolution) {
        val nextAttempt = request.attempt + 1
        val delayMillis = QiShuiResolutionPolicy.delayBeforeAttempt(nextAttempt)
        if (nextAttempt >= QiShuiResolutionPolicy.MAX_ATTEMPTS || delayMillis == null) {
            val metadata = request.metadata
            logResolutionOnce(
                "resolution-miss:${request.mediaId}",
                "event=resolutionExhausted mediaId=${request.mediaId} " +
                    "attempts=${QiShuiResolutionPolicy.MAX_ATTEMPTS}"
            )
            commitSong(
                song = Song(
                    id = request.mediaId,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    duration = metadata?.duration ?: 0L
                ),
                trackGeneration = request.trackGeneration,
                rememberMissing = true,
                reason = "resolution-miss"
            )
            clearPendingLyricResolution(request.mediaId)
            return
        }

        val scheduled = request.copy(attempt = nextAttempt, inFlight = false)
        pendingLyricResolution = scheduled
        if (nextAttempt == 1) {
            QiShuiLog.debug(
                "event=resolutionPending mediaId=${request.mediaId} " +
                    "maxAttempts=${QiShuiResolutionPolicy.MAX_ATTEMPTS} " +
                    "nextDelay=${delayMillis}ms"
            )
        }
        mainHandler.postDelayed(
            {
                val current = pendingLyricResolution ?: return@postDelayed
                if (current.token != scheduled.token ||
                    current.mediaId != scheduled.mediaId ||
                    current.trackGeneration != scheduled.trackGeneration ||
                    current.attempt != scheduled.attempt ||
                    current.inFlight ||
                    curMediaId != scheduled.mediaId ||
                    currentTrackGeneration != scheduled.trackGeneration
                ) {
                    return@postDelayed
                }
                val inFlight = current.copy(inFlight = true)
                pendingLyricResolution = inFlight
                startSongResolution(inFlight)
            },
            delayMillis
        )
    }

    private fun cancelPendingLyricResolution() {
        if (pendingLyricResolution != null) {
            nextResolutionToken()
            pendingLyricResolution = null
        }
    }

    private fun clearPendingLyricResolution(id: String) {
        if (pendingLyricResolution?.mediaId == id) {
            nextResolutionToken()
            pendingLyricResolution = null
        }
    }

    private fun nextResolutionToken(): Long {
        resolutionToken = if (resolutionToken == Long.MAX_VALUE) 1L else resolutionToken + 1L
        return resolutionToken
    }

    private fun commitSong(
        song: Song,
        trackGeneration: Long,
        rememberMissing: Boolean = false,
        reason: String
    ) {
        val songId = song.id?.takeIf { it.isNotBlank() }
        if (songId != null &&
            (songId != curMediaId || trackGeneration != currentTrackGeneration)
        ) {
            QiShuiLog.debug(
                "event=songCommitSkipped reason=stale id=$songId generation=$trackGeneration " +
                    "currentId=${curMediaId.orEmpty()}, currentGeneration=$currentTrackGeneration"
            )
            return
        }
        val preparedSong = bridgeSongWithCurrentMetadata(song)
        if (preparedSong == lastSong && trackGeneration == lastSongGeneration) return

        val playback = resolvePlaybackForCommit(trackGeneration, preparedSong.duration)
        val player = provider?.player
        dispatchQiShuiSongCommit(
            setSong = {
                runCatching { player?.setSong(preparedSong) }
                    .onFailure { QiShuiLog.error("event=setSongFailed", it) }
            },
            setPosition = playback?.position?.let { position ->
                {
                    runCatching { player?.setPosition(position) }
                        .onFailure { QiShuiLog.error("event=setPositionFailed", it) }
                }
            },
            replayPlaybackState = playback?.state?.let { state ->
                {
                    runCatching { player?.setPlaybackState(state) }
                        .onFailure { QiShuiLog.error("event=stateReplayFailed", it) }
                }
            },
            publishLyricReady = {
                SaltLyricBridge.send(appContext, preparedSong, trackGeneration)
            },
            publishPlaybackState = playback?.state?.let { state ->
                {
                    SaltLyricBridge.sendPlaybackState(
                        context = appContext,
                        state = state,
                        mediaId = preparedSong.id,
                        title = preparedSong.name,
                        artist = preparedSong.artist,
                        duration = preparedSong.duration,
                        trackGeneration = trackGeneration,
                        force = true
                    )
                }
            }
        )
        QiShuiLog.debug(
            "event=songCommitted reason=$reason id=${preparedSong.id.orEmpty()} " +
                "generation=$trackGeneration lyrics=${preparedSong.lyrics?.size ?: 0} " +
                "position=${playback?.position ?: -1L} state=${playback?.state?.state ?: -1}"
        )
        lastSong = preparedSong
        lastSongGeneration = trackGeneration
        if (!preparedSong.id.isNullOrBlank() && !preparedSong.lyrics.isNullOrEmpty()) {
            rememberSong(preparedSong)
        } else if (rememberMissing) {
            rememberMissingSong(preparedSong)
        }
    }

    private data class PlaybackCommit(
        val state: PlaybackState,
        val position: Long?
    )

    private fun resolvePlaybackForCommit(
        trackGeneration: Long,
        duration: Long
    ): PlaybackCommit? {
        val session = authoritativeSession.get() ?: return null
        val sessionIdentity = System.identityHashCode(session)
        val queriedState = runCatching { session.controller.playbackState }
            .onFailure {
                QiShuiLog.debug(
                    "event=playbackQueryFailed type=${it.javaClass.simpleName}"
                )
            }
            .getOrNull()
        if (queriedState != null) {
            latestPlaybackState = queriedState
            latestPlaybackSnapshot = playbackSnapshot(session, queriedState, trackGeneration)
        }

        val snapshot = QiShuiPlaybackPolicy.snapshotForCommit(
            snapshot = latestPlaybackSnapshot,
            trackGeneration = trackGeneration,
            sessionIdentity = sessionIdentity
        ) ?: return null
        val state = latestPlaybackState ?: return null
        val position = QiShuiPlaybackPolicy.projectPosition(
            snapshot = snapshot,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            duration = duration
        )
        return PlaybackCommit(state, position)
    }

    private fun playbackSnapshot(
        session: MediaSession,
        state: PlaybackState,
        trackGeneration: Long
    ): QiShuiPlaybackSnapshot {
        return QiShuiPlaybackSnapshot(
            state = state.state,
            position = state.position,
            speed = state.playbackSpeed,
            lastPositionUpdateTime = state.lastPositionUpdateTime,
            moving = isPlaybackInMotion(state.state),
            trackGeneration = trackGeneration,
            sessionIdentity = System.identityHashCode(session),
            observedAtElapsedMillis = SystemClock.elapsedRealtime()
        )
    }

    private fun bridgeSongWithCurrentMetadata(song: Song): Song {
        val id = song.id?.takeIf { it.isNotBlank() } ?: return song
        val metadata = MetadataCache.get(id) ?: return song
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: song.name
        val artist = metadata.artist?.takeIf { it.isNotBlank() } ?: song.artist
        val duration = metadata.duration.takeIf { it > 0L } ?: song.duration
        if (title == song.name && artist == song.artist && duration == song.duration) return song
        return Song(
            id = id,
            name = title,
            artist = artist,
            duration = duration,
            lyrics = song.lyrics
        )
    }

    private fun acceptOfficialSong(song: Song, source: String, observedGeneration: Long) {
        runOnMain { acceptOfficialSongOnMain(song, source, observedGeneration) }
    }

    private fun acceptOfficialSongOnMain(
        song: Song,
        source: String,
        observedGeneration: Long
    ) {
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        if (song.lyrics.isNullOrEmpty()) return

        if (!QiShuiPlaybackPolicy.acceptsOfficialCandidate(
                currentMediaId = curMediaId,
                currentGeneration = currentTrackGeneration,
                candidateMediaId = id,
                observedGeneration = observedGeneration
            )
        ) {
            QiShuiLog.debug(
                "event=officialCandidateRejected source=$source mediaId=$id " +
                    "observedGeneration=$observedGeneration currentId=${curMediaId.orEmpty()} " +
                    "currentGeneration=$currentTrackGeneration"
            )
            return
        }

        rememberOfficialSong(song)
        cancelPendingLyricResolution()
        commitSong(
            song = song,
            trackGeneration = currentTrackGeneration,
            reason = "official:$source"
        )
    }

    private fun getCachedSong(id: String): Song? {
        synchronized(songCache) {
            return songCache[id]
        }
    }

    private fun getCachedOfficialSong(id: String): Song? {
        synchronized(officialSongCache) {
            return officialSongCache[id]
        }
    }

    private fun rememberOfficialSong(song: Song) {
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        synchronized(officialSongCache) {
            officialSongCache[id] = song
        }
        synchronized(missingSongCache) {
            missingSongCache.remove(id)
        }
    }

    private fun rememberSong(song: Song) {
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        synchronized(songCache) {
            songCache[id] = song
        }
        synchronized(missingSongCache) {
            missingSongCache.remove(id)
        }
    }

    private fun getCachedMissingSong(id: String): MissingSong? {
        val now = SystemClock.elapsedRealtime()
        synchronized(missingSongCache) {
            val cached = missingSongCache[id] ?: return null
            if (cached.expiresAtElapsedMillis > now) {
                return cached
            }
            missingSongCache.remove(id)
            return null
        }
    }

    private fun rememberMissingSong(song: Song) {
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        synchronized(missingSongCache) {
            missingSongCache[id] = MissingSong(
                song = song,
                expiresAtElapsedMillis = SystemClock.elapsedRealtime() +
                    QiShuiResolutionPolicy.NEGATIVE_CACHE_TTL_MS
            )
        }
    }

    private fun logResolutionOnce(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(resolutionLogAtByKey) {
            val lastLoggedAt = resolutionLogAtByKey[key]
            if (lastLoggedAt != null && now - lastLoggedAt < RESOLUTION_LOG_THROTTLE_MS) {
                return
            }
            resolutionLogAtByKey[key] = now
        }
        QiShuiLog.debug(message)
    }

    private fun NetResponseCache.buildSong(id: String, metadata: Metadata?): Song {
        return Song(
            id = id,
            name = metadata?.title.orEmpty(),
            artist = metadata?.artist.orEmpty(),
            duration = metadata?.duration ?: 0L,
            lyrics = toRichLyric()
        )
    }

    private val netCacheLoaderDir by lazy { appContext!!.cacheDir.resolve("NetCacheLoader") }

    private fun getNetLyricCacheFile(id: String): File? {
        val fileName = calculateLyricCacheFileName(id)

        return runCatching {
            var targetFile: File? = null
            netCacheLoaderDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name == fileName) {
                        targetFile = file
                        return@forEach
                    }
                }
                if (targetFile != null) return@forEach
            }
            targetFile ?: findNetLyricCacheFileRecursively(id, fileName)
        }.onFailure {
            QiShuiLog.warningOnce(
                key = "net-cache-find:$id",
                message = "event=netCacheFindFailed mediaId=$id",
                throwable = it
            )
        }.getOrNull()
    }

    private fun findNetLyricCacheFileRecursively(id: String, fileName: String): File? {
        val start = SystemClock.elapsedRealtime()
        var scannedDirs = 0
        var scannedFiles = 0
        var found: File? = null
        val root = netCacheLoaderDir
        if (!root.exists() || !root.isDirectory) {
            logNetCacheDiagnostic(
                id = id,
                scannedDirs = scannedDirs,
                scannedFiles = scannedFiles,
                found = null,
                elapsedMs = SystemClock.elapsedRealtime() - start
            )
            return null
        }

        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && scannedFiles < NET_CACHE_RECURSIVE_MAX_FILES) {
            val dir = stack.removeLast()
            scannedDirs++
            dir.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile) {
                    scannedFiles++
                    if (child.name == fileName) {
                        found = child
                        return@forEach
                    }
                }
            }
            if (found != null) break
        }

        logNetCacheDiagnostic(
            id = id,
            scannedDirs = scannedDirs,
            scannedFiles = scannedFiles,
            found = found,
            elapsedMs = SystemClock.elapsedRealtime() - start
        )
        if (found != null) {
            QiShuiLog.debug("event=netCacheRecursiveHit mediaId=$id")
        }
        return found
    }

    private fun logNetCacheDiagnostic(
        id: String,
        scannedDirs: Int,
        scannedFiles: Int,
        found: File?,
        elapsedMs: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(netCacheDiagnosticLogAtById) {
            val lastLoggedAt = netCacheDiagnosticLogAtById[id]
            if (lastLoggedAt != null && now - lastLoggedAt < NET_CACHE_DIAG_THROTTLE_MS) {
                return
            }
            netCacheDiagnosticLogAtById[id] = now
        }

        QiShuiLog.debug(
            "event=netCacheScan mediaId=$id scannedDirs=$scannedDirs " +
                "scannedFiles=$scannedFiles found=${found != null} elapsedMs=$elapsedMs"
        )
    }

    private fun calculateLyricCacheFileName(id: String): String =
        "/luna/track_v2/$id".md5()
}
