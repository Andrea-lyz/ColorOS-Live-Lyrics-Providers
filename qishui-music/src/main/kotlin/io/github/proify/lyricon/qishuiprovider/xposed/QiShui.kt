package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
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
import java.util.LinkedHashMap

object QiShui : YukiBaseHooker() {

    private const val TAG = "QiShui"
    private const val SONG_CACHE_MAX_ENTRIES = 32
    private const val MISSING_SONG_CACHE_MAX_ENTRIES = 32
    private const val MISSING_SONG_CACHE_TTL_MS = 1_000L
    private const val RESOLUTION_LOG_THROTTLE_MS = 10_000L
    private const val ACTION_TOGGLE_TRANSLATION =
        "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
    private const val TRANSLATION_ACTION_NAME = "\u7ffb\u8bd1"
    private const val LYRIC_RETRY_MAX_ATTEMPTS = 24
    private var provider: LyriconProvider? = null

    private var curMediaId: String? = null
    private var currentTrackGeneration = 0L
    private var lastSong: Song? = null
    private var translationActionInjectionLogged = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var lyricRetryGeneration = 0L
    private var pendingLyricResolution: PendingLyricResolution? = null

    private data class MissingSong(
        val song: Song,
        val expiresAtMillis: Long
    )

    private data class PendingLyricResolution(
        val mediaId: String,
        val trackGeneration: Long,
        val nextAttempt: Int,
        val generation: Long
    )

    private val songCache = object : LinkedHashMap<String, Song>(
        SONG_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean {
            return size > SONG_CACHE_MAX_ENTRIES
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

    override fun onHook() {
        YLog.info(tag = TAG, msg = "$packageName/$processName")

        onAppLifecycle {
            onCreate {
                hook()
            }
        }
    }

    private var hooked = false
    private fun hook() {
        if (hooked) {
            YLog.info(tag = TAG, msg = "already hooked")
            return
        }
        hooked = true

        initProvider()
        hookMediaSession()
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
        YLog.debug(tag = TAG, msg = "provider registered, provider=${provider?.providerInfo}")
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
                                YLog.info(
                                    tag = TAG,
                                    msg = "Injected translation toggle action into QiShui PlaybackState"
                                )
                            }
                        }
                    }
                    after {
                        val state = args[0] as? PlaybackState
                        provider?.player?.setPlaybackState(state)
                        SaltLyricBridge.sendPlaybackState(appContext, state)
                        updateSongIfNeed()
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        val id = MetadataCache.resolveId(mediaMetadata)
                        val metadata = MetadataCache.save(mediaMetadata, id)

                        if (id.isNullOrBlank()) {
                            val description = mediaMetadata.description
                            val title = description.title?.toString()
                            val artist = description.subtitle?.toString()
                            if (title.isNullOrBlank() && artist.isNullOrBlank()) {
                                logResolutionOnce(
                                    "blank-metadata",
                                    "ignore blank metadata without media id"
                                )
                                return@after
                            }
                            setSong(
                                Song(
                                    name = title,
                                    artist = artist
                                )
                            )
                            return@after
                        }

                        if (curMediaId == id) return@after
                        cancelPendingLyricResolution()
                        curMediaId = id
                        currentTrackGeneration++
                        YLog.info(
                            tag = TAG,
                            msg = "metadata id=${id.orEmpty()}, " +
                                "title=${metadata?.title.orEmpty()}, " +
                                "artist=${metadata?.artist.orEmpty()}"
                        )
                        SaltLyricBridge.sendTrackChanged(
                            context = appContext,
                            mediaId = id,
                            title = metadata?.title,
                            artist = metadata?.artist,
                            duration = metadata?.duration ?: 0L,
                            trackGeneration = currentTrackGeneration
                        )
                        updateSong()
                    }
                }
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
        val builder = PlaybackState.Builder(original)
        if (!clearPlaybackStateBuilderCustomActions(builder)) {
            builder.addCustomAction(translationAction)
            return builder.build()
        }
        builder.addCustomAction(translationAction)
        originalActions.forEach { action ->
            if (action.action != ACTION_TOGGLE_TRANSLATION) {
                builder.addCustomAction(action)
            }
        }
        return builder.build()
    }

    private fun clearPlaybackStateBuilderCustomActions(
        builder: PlaybackState.Builder
    ): Boolean = runCatching {
        val field = PlaybackState.Builder::class.java.getDeclaredField("mCustomActions")
        field.isAccessible = true
        val actions = field.get(builder) as? MutableList<*> ?: return@runCatching false
        actions.clear()
        true
    }.getOrDefault(false)

    private fun resolveTranslationActionPlaceholderIcon(state: PlaybackState): Int {
        state.customActions.orEmpty().firstOrNull { it.icon != 0 }?.let { return it.icon }
        appContext?.applicationInfo?.icon?.takeIf { it != 0 }?.let { return it }
        return android.R.drawable.ic_menu_manage
    }

    private fun updateSongIfNeed() {
        if (curMediaId.isNullOrBlank()) return
        val lastSong = this.lastSong
        if (lastSong?.lyrics.isNullOrEmpty()) updateSong()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun updateSong() {
        updateSongInternal(fromRetry = false)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun updateSongInternal(fromRetry: Boolean) {
        val id = curMediaId ?: return
        val trackGeneration = currentTrackGeneration
        if (!fromRetry && hasPendingLyricResolution(id)) {
            return
        }
        getCachedSong(id)?.let { cached ->
            YLog.debug(
                tag = TAG,
                msg = "memory cache hit, mediaId=$id, lyrics=${cached.lyrics?.size ?: 0}"
            )
            setSong(cached, trackGeneration = trackGeneration)
            clearPendingLyricResolution(id)
            return
        }
        getCachedMissingSong(id)?.let { missing ->
            logResolutionOnce(
                "missing-cache:$id",
                "missing lyric cache hit, mediaId=$id"
            )
            if (lastSong?.id != id) {
                setSong(missing.song, rememberMissing = false, trackGeneration = trackGeneration)
            }
            return
        }

        val cache = runCatching {
            val file = getNetLyricCacheFile(id)
            if (file != null && file.exists()) {
                YLog.info(tag = TAG, msg = "cache hit, mediaId=$id, file=${file.name}")
                file.inputStream().use {
                    json.decodeFromStream<NetResponseCache>(it)
                }
            } else null
        }.onFailure {
            YLog.error(tag = TAG, msg = "cache load failed, mediaId=$id, error=$it")
        }.getOrNull()

        val metadata = MetadataCache.get(id)
        if (cache != null) {
            val song = cache.buildSong(id)
            if (!song.lyrics.isNullOrEmpty()) {
                setSong(song, trackGeneration = trackGeneration)
                clearPendingLyricResolution(id)
                return
            }
            YLog.info(tag = TAG, msg = "cache lyric empty, mediaId=$id")
        } else {
            logResolutionOnce(
                "cache-miss:$id",
                "cache miss, mediaId=$id, file=${calculateLyricCacheFileName(id)}"
            )
        }

        val dbHit = PlayableDbCache.findSong(appContext, id, metadata)
        if (dbHit != null) {
            YLog.info(
                tag = TAG,
                msg = "db hit, mediaId=$id, db=${dbHit.databaseName}, " +
                    "table=${dbHit.tableName}, lyrics=${dbHit.song.lyrics?.size ?: 0}"
            )
            setSong(dbHit.song, trackGeneration = trackGeneration)
            clearPendingLyricResolution(id)
            return
        }

        if (scheduleLyricResolutionRetry(id, trackGeneration)) {
            return
        }

        logResolutionOnce("db-miss:$id", "db miss, mediaId=$id")
        setSong(
            Song(
                id = id,
                name = metadata?.title,
                artist = metadata?.artist,
                duration = metadata?.duration ?: 0L
            ),
            rememberMissing = true,
            trackGeneration = trackGeneration
        )
        clearPendingLyricResolution(id)
    }

    private fun hasPendingLyricResolution(id: String): Boolean =
        pendingLyricResolution?.mediaId == id

    private fun cancelPendingLyricResolution() {
        if (pendingLyricResolution != null) {
            lyricRetryGeneration++
            pendingLyricResolution = null
        }
    }

    private fun clearPendingLyricResolution(id: String) {
        if (pendingLyricResolution?.mediaId == id) {
            lyricRetryGeneration++
            pendingLyricResolution = null
        }
    }

    private fun scheduleLyricResolutionRetry(id: String, trackGeneration: Long): Boolean {
        if (id.isBlank()) return false
        val nextAttempt = pendingLyricResolution
            ?.takeIf { it.mediaId == id && it.trackGeneration == trackGeneration }
            ?.nextAttempt
            ?: 0
        if (nextAttempt >= LYRIC_RETRY_MAX_ATTEMPTS) {
            clearPendingLyricResolution(id)
            return false
        }

        val delayMillis = lyricRetryDelayMillis(nextAttempt)
        val generation = ++lyricRetryGeneration
        pendingLyricResolution = PendingLyricResolution(
            mediaId = id,
            trackGeneration = trackGeneration,
            nextAttempt = nextAttempt + 1,
            generation = generation
        )
        logResolutionOnce(
            "lyrics-pending:$id:$nextAttempt",
            "lyrics pending, mediaId=$id, retry=${nextAttempt + 1}/$LYRIC_RETRY_MAX_ATTEMPTS, " +
                "delay=${delayMillis}ms"
        )
        mainHandler.postDelayed(
            {
                if (curMediaId != id) return@postDelayed
                val pending = pendingLyricResolution ?: return@postDelayed
                if (pending.mediaId != id ||
                    pending.trackGeneration != trackGeneration ||
                    pending.generation != generation
                ) {
                    return@postDelayed
                }
                updateSongInternal(fromRetry = true)
            },
            delayMillis
        )
        return true
    }

    private fun lyricRetryDelayMillis(attempt: Int): Long {
        return when {
            attempt <= 0 -> 400L
            attempt == 1 -> 600L
            attempt == 2 -> 800L
            attempt == 3 -> 1_200L
            attempt == 4 -> 1_600L
            attempt == 5 -> 2_000L
            attempt == 6 -> 2_500L
            attempt == 7 -> 3_000L
            attempt == 8 -> 4_000L
            else -> 5_000L
        }
    }

    private fun setSong(
        song: Song,
        rememberMissing: Boolean = false,
        trackGeneration: Long = currentTrackGeneration
    ) {
        if (song == lastSong) return
        provider?.player?.setSong(song)
        SaltLyricBridge.send(appContext, song, trackGeneration)
        YLog.info(
            tag = TAG,
            msg = "song updated, id=${song.id.orEmpty()}, " +
                "title=${song.name.orEmpty()}, lyrics=${song.lyrics?.size ?: 0}"
        )
        lastSong = song
        if (!song.id.isNullOrBlank() && !song.lyrics.isNullOrEmpty()) {
            rememberSong(song)
        } else if (rememberMissing) {
            rememberMissingSong(song)
        }
    }

    private fun getCachedSong(id: String): Song? {
        synchronized(songCache) {
            return songCache[id]
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
        val now = System.currentTimeMillis()
        synchronized(missingSongCache) {
            val cached = missingSongCache[id] ?: return null
            if (cached.expiresAtMillis > now) {
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
                expiresAtMillis = System.currentTimeMillis() + MISSING_SONG_CACHE_TTL_MS
            )
        }
    }

    private fun logResolutionOnce(key: String, message: String) {
        val now = System.currentTimeMillis()
        synchronized(resolutionLogAtByKey) {
            val lastLoggedAt = resolutionLogAtByKey[key]
            if (lastLoggedAt != null && now - lastLoggedAt < RESOLUTION_LOG_THROTTLE_MS) {
                return
            }
            resolutionLogAtByKey[key] = now
        }
        YLog.info(tag = TAG, msg = message)
    }

    fun NetResponseCache.buildSong(id: String): Song {
        val metadata = MetadataCache.get(id)
        return Song(
            id = id,
            name = metadata?.title.orEmpty(),
            artist = metadata?.artist.orEmpty(),
            duration = metadata?.duration ?: 0L,
            lyrics = toRichLyric()
        )
    }

    private val netCacheLoaderDir by lazy { appContext!!.cacheDir.resolve("NetCacheLoader") }

    fun getNetLyricCacheFile(id: String): File? {
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
            targetFile
        }.onFailure {
            YLog.error(tag = TAG, msg = "getNetLyricCacheFile failed, mediaId=$id, error=$it")
        }.getOrNull()
    }

    fun calculateLyricCacheFileName(id: String): String =
        "/luna/track_v2/$id".md5()
}
