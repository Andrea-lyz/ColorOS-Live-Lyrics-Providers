/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import android.content.Context
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

object MetrolistLyricsFetcher {
    const val PER_PROVIDER_TIMEOUT_MS = 8000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val implementedProviders = setOf("BetterLyrics", "LrcLib", "KuGou")

    suspend fun fetchLyrics(
        context: Context,
        track: TrackIdentity,
        generation: Long? = null
    ): MetrolistPublication? {
        val selection = MetrolistProviderPreferences.read(context)
        val enabledProviders = selection.order.filter { name ->
            (name in implementedProviders) && (selection.enabled[name] != false)
        }
        logLyric(
            event = "LYRIC_PROVIDERS_SELECTED",
            generation = generation,
            reason = selection.source,
            message = enabledProviders.joinToString(",").ifEmpty { "none" }
        )
        if (enabledProviders.isEmpty()) return null

        val title = track.title.orEmpty()
        val artist = track.artist.orEmpty()
        val durationSeconds = MetrolistKuGouMatchPolicy.queryDurationSeconds(track.durationMs)
        val album = track.album

        val hit = SequentialProviderFetcher.firstUsable(
            providers = enabledProviders,
            timeoutMillis = PER_PROVIDER_TIMEOUT_MS,
            fetch = { provider ->
                fetchFromProvider(provider, title, artist, durationSeconds, album, track, generation)
            },
            isUsable = { publication -> publication.lines.any { !it.text.isNullOrBlank() } },
            onTimeout = { provider ->
                logLyric(
                    event = "LYRIC_PROVIDER_MISS",
                    generation = generation,
                    reason = provider,
                    message = "timeout"
                )
            },
            onFailure = { provider, error ->
                logLyric(
                    event = "LYRIC_PROVIDER_MISS",
                    generation = generation,
                    reason = provider,
                    message = error.javaClass.simpleName + ":" + (error.message?.take(180) ?: "")
                )
            }
        )
        return hit?.value
    }

    private suspend fun fetchFromProvider(
        provider: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        track: TrackIdentity,
        generation: Long?
    ): MetrolistPublication? {
        logLyric(
            event = "LYRIC_PROVIDER_TRY",
            generation = generation,
            reason = provider,
            message = "title=" + title + " artist=" + artist + " durationSec=" + duration
        )
        val publication = when (provider) {
            "BetterLyrics" -> fetchBetterLyrics(title, artist, duration, album, track, generation)
            "LrcLib" -> fetchLrcLib(title, artist, duration, album, track, generation)
            "KuGou" -> fetchKuGou(title, artist, duration, album, track, generation)
            else -> {
                logLyric("LYRIC_PROVIDER_MISS", generation, provider, "unsupported")
                null
            }
        }
        if (publication != null) {
            logLyric(
                event = "LYRIC_PROVIDER_HIT",
                generation = generation,
                reason = provider,
                message = "lines=" + publication.lines.size
            )
        }
        return publication
    }

    @Serializable
    private data class BetterLyricsResponse(
        val ttml: String? = null,
        val lyrics: String? = null
    )

    internal fun buildBetterLyricsUrl(
        title: String,
        artist: String,
        duration: Int,
        album: String?
    ): String = buildString {
        append("https://lyrics-api.boidu.dev/getLyrics?")
        append("s=").append(URLEncoder.encode(title, "UTF-8"))
        append("&a=").append(URLEncoder.encode(artist, "UTF-8"))
        if (duration > 0) append("&d=").append(duration)
        if (!album.isNullOrBlank()) {
            append("&al=").append(URLEncoder.encode(album, "UTF-8"))
        }
    }

    private suspend fun fetchBetterLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        track: TrackIdentity,
        generation: Long?
    ): MetrolistPublication? {
        val request = Request.Builder()
            .url(buildBetterLyricsUrl(title, artist, duration, album))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        val body = executeRequest(request).use { response ->
            if (!response.isSuccessful) {
                logLyric("LYRIC_PROVIDER_MISS", generation, "BetterLyrics", "http-" + response.code)
                return null
            }
            response.body.string()
        }
        val ttmlResponse = json.decodeFromString<BetterLyricsResponse>(body)
        val payload = (ttmlResponse.ttml ?: ttmlResponse.lyrics)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (payload == null) {
            logLyric("LYRIC_PROVIDER_MISS", generation, "BetterLyrics", "empty")
            return null
        }
        val decoded = MetrolistLyricDecoder.decodeBetterLyricsPayload(payload, track, "BetterLyrics")
        if (decoded == null) {
            logLyric("LYRIC_PROVIDER_MISS", generation, "BetterLyrics", "decode")
        }
        return decoded
    }

    @Serializable
    private data class LrcLibTrack(
        val id: Int = 0,
        val trackName: String = "",
        val artistName: String = "",
        val duration: Double = 0.0,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null
    )

    private suspend fun fetchLrcLib(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        track: TrackIdentity,
        generation: Long?
    ): MetrolistPublication? {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        var sawCandidate = false
        suspend fun attempt(tracks: List<LrcLibTrack>): MetrolistPublication? {
            val lyric = selectBestLrcLibTrack(tracks, duration) ?: return null
            sawCandidate = true
            return MetrolistLyricDecoder.decode(lyric, track, "LrcLib", track.durationMs)
        }
        attempt(queryLrcLib(trackName = cleanedTitle, artistName = cleanedArtist, albumName = album))
            ?.let { return it }
        attempt(queryLrcLib(trackName = cleanedTitle))?.let { return it }
        attempt(queryLrcLib(query = "$cleanedArtist $cleanedTitle"))?.let { return it }
        logLyric(
            "LYRIC_PROVIDER_MISS",
            generation,
            "LrcLib",
            if (sawCandidate) "untimed" else "empty"
        )
        return null
    }

    private suspend fun queryLrcLib(
        trackName: String? = null,
        artistName: String? = null,
        albumName: String? = null,
        query: String? = null
    ): List<LrcLibTrack> {
        return try {
            val url = buildString {
                append("https://lrclib.net/api/search?")
                trackName?.let { append("track_name=").append(URLEncoder.encode(it, "UTF-8")).append("&") }
                artistName?.let { append("artist_name=").append(URLEncoder.encode(it, "UTF-8")).append("&") }
                albumName?.takeIf(String::isNotBlank)?.let {
                    append("album_name=").append(URLEncoder.encode(it, "UTF-8")).append("&")
                }
                query?.let { append("q=").append(URLEncoder.encode(it, "UTF-8")).append("&") }
            }
            val request = Request.Builder().url(url).build()
            val body = executeRequest(request).use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body.string()
            }
            json.decodeFromString<List<LrcLibTrack>>(body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun selectBestLrcLibTrack(tracks: List<LrcLibTrack>, duration: Int): String? {
        val withLyrics = tracks.filter { it.syncedLyrics != null || it.plainLyrics != null }
        if (withLyrics.isEmpty()) return null
        val synced = withLyrics.filter { it.syncedLyrics != null }
        if (synced.isNotEmpty() && duration > 0) {
            val best = synced.minByOrNull { abs(it.duration.toInt() - duration) }
            if (best != null && abs(best.duration.toInt() - duration) <= 5) {
                return best.syncedLyrics
            }
        }
        return synced.firstOrNull()?.syncedLyrics ?: withLyrics.firstOrNull()?.plainLyrics
    }

    @Serializable
    private data class KuGouSearchSongResponse(val data: KuGouSongData = KuGouSongData())

    @Serializable
    private data class KuGouSongData(val info: List<KuGouSongInfo> = emptyList())

    @Serializable
    private data class KuGouSongInfo(val hash: String = "", val duration: Int = 0)

    @Serializable
    private data class KuGouSearchLyricsResponse(val candidates: List<KuGouCandidate> = emptyList())

    @Serializable
    private data class KuGouCandidate(val id: Long = 0, val accesskey: String = "")

    @Serializable
    private data class KuGouDownloadResponse(val content: String = "")

    private suspend fun fetchKuGou(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        track: TrackIdentity,
        generation: Long?
    ): MetrolistPublication? {
        val normalizedTitle = title.replace(Regex("\\(.*\\)"), "").trim()
        val normalizedArtist = artist.replace(", ", "、").replace(" & ", "、").trim()
        val keyword = buildString {
            append(normalizedTitle).append(" - ").append(normalizedArtist)
            album?.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
        }
        val searchUrl = buildString {
            append("https://mobileservice.kugou.com/api/v3/search/song?")
            append("version=9108&plat=0&pagesize=8&showtype=0&")
            append("keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        val searchResponse = executeJson<KuGouSearchSongResponse>(searchUrl)
        if (searchResponse == null) {
            logLyric("LYRIC_PROVIDER_MISS", generation, "KuGou", "search-http")
            return null
        }
        for (song in searchResponse.data.info) {
            if (MetrolistKuGouMatchPolicy.matchesSongDuration(song.duration, duration)) {
                val lyricsUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=" + song.hash
                val lyricsResponse = executeJson<KuGouSearchLyricsResponse>(lyricsUrl)
                val candidate = lyricsResponse?.candidates?.firstOrNull()
                if (candidate != null) {
                    val lyrics = downloadKuGouLyrics(candidate.id, candidate.accesskey, track)
                    if (lyrics != null) return lyrics
                }
            }
        }
        val keywordLyricsUrl = buildString {
            append("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&")
            if (duration > 0) append("duration=${duration * 1000}&")
            append("keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        val keywordResponse = executeJson<KuGouSearchLyricsResponse>(keywordLyricsUrl)
        val candidate = keywordResponse?.candidates?.firstOrNull()
        if (candidate == null) {
            logLyric("LYRIC_PROVIDER_MISS", generation, "KuGou", "no-candidate")
            return null
        }
        val downloaded = downloadKuGouLyrics(candidate.id, candidate.accesskey, track)
        if (downloaded == null) {
            logLyric("LYRIC_PROVIDER_MISS", generation, "KuGou", "download-decode")
        }
        return downloaded
    }

    private suspend fun downloadKuGouLyrics(
        id: Long,
        accessKey: String,
        track: TrackIdentity
    ): MetrolistPublication? {
        downloadKuGouContent(id, accessKey, "krc")
            ?.let { MetrolistLyricDecoder.decodeEncryptedKrc(it, track, "KuGou") }
            ?.let { return it }
        val content = downloadKuGouContent(id, accessKey, "lrc") ?: return null
        val decoded = decodeLineLrc(content) ?: return null
        return MetrolistLyricDecoder.decode(decoded, track, "KuGou", track.durationMs)
    }

    private suspend fun downloadKuGouContent(
        id: Long,
        accessKey: String,
        format: String
    ): String? {
        val url = "https://lyrics.kugou.com/download?fmt=$format&charset=utf8&client=pc" +
            "&ver=1&id=$id&accesskey=$accessKey"
        return executeJson<KuGouDownloadResponse>(url)?.content?.takeIf { it.isNotEmpty() }
    }

    private fun decodeLineLrc(base64Content: String): String? {
        return runCatching {
            val decoded = String(android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT), Charsets.UTF_8)
            decoded.lines()
                .filter { it.matches(Regex("\\[(\\d{1,3}):(\\d{2})\\.(\\d{2,3})].*")) }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private suspend inline fun <reified T> executeJson(url: String): T? {
        return try {
            val request = Request.Builder().url(url).build()
            val body = executeRequest(request).use { response ->
                if (!response.isSuccessful) return null
                response.body.string()
            }
            json.decodeFromString<T>(body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeRequest(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE)
    )

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        val separators = listOf(" & ", " and ", ", ", " x ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")
        for (sep in separators) {
            if (cleaned.contains(sep, ignoreCase = true)) {
                cleaned = cleaned.split(sep, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private fun logLyric(
        event: String,
        generation: Long?,
        reason: String?,
        message: String? = null
    ) {
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/metrolist",
                area = "lyric",
                event = event,
                generation = generation,
                reason = reason,
                message = message
            )
        )
    }
}
