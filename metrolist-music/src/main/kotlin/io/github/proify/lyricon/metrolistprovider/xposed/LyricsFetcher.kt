/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.proify.lyricon.krckit.KrcDecryptor
import io.github.proify.lyricon.krckit.KrcParser
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

/**
 * Multi-provider lyrics fetcher that respects the user's configured provider
 * order from Metrolist's DataStore preferences.
 *
 * Providers implemented: BetterLyrics, LrcLib, KuGou.
 * Falls back to default order if DataStore cannot be read.
 */
object LyricsFetcher {
    private const val TAG = "MetrolistProvider"
    private const val PER_PROVIDER_TIMEOUT_MS = 8000L
    private val INLINE_WORD_TIME_REGEX =
        Regex("""<[0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?>""")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val implementedProviders = setOf("BetterLyrics", "LrcLib", "KuGou")
    // region Main fetch logic

    /** Result of lyrics fetch: plain LRC for display + enhanced LRC with word timing. */
    data class LyricsResult(val plainLyric: String, val rawLyric: String)

    /**
     * Fetches lyrics by trying providers in the user's configured order.
     * Returns both plain and enhanced (word-timed) LRC, or null if all fail.
     */
    suspend fun fetchLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null
    ): LyricsResult? {
        val selection = MetrolistProviderPreferences.read(context)
        val enabledProviders = selection.order.filter { name ->
            (name in implementedProviders) && (selection.enabled[name] != false)
        }

        Log.i(TAG, "Trying providers in order: $enabledProviders (source=${selection.source})")

        val hit = SequentialProviderFetcher.firstUsable(
            providers = enabledProviders,
            timeoutMillis = PER_PROVIDER_TIMEOUT_MS,
            fetch = { provider -> fetchFromProvider(provider, title, artist, duration, album) },
            isUsable = { result -> result.plainLyric.isNotEmpty() },
            onTimeout = { provider ->
                Log.d(TAG, "$provider timed out after ${PER_PROVIDER_TIMEOUT_MS}ms for: $title")
            },
            onFailure = { provider, error ->
                Log.d(TAG, "$provider failed for $title: ${error.message}")
            }
        )
        if (hit != null) {
            val timing = if (hit.value.hasWordTiming()) "word" else "line"
            Log.i(TAG, "Got lyrics from ${hit.provider} for: $title (timing=$timing)")
            return hit.value
        }

        Log.i(TAG, "All providers failed for: $title")
        return null
    }

    private suspend fun fetchFromProvider(
        provider: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?
    ): LyricsResult? = when (provider) {
        "BetterLyrics" -> fetchBetterLyrics(title, artist, duration, album)
        "LrcLib" -> fetchLrcLib(title, artist, duration, album)?.let { LyricsResult(it, it) }
        "KuGou" -> fetchKuGou(title, artist, duration, album)
        else -> null
    }

    // endregion

    // region BetterLyrics (https://lyrics-api.boidu.dev)

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
        album: String?
    ): LyricsResult? {
        val url = buildBetterLyricsUrl(title, artist, duration, album)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()

        val body = executeRequest(request).use { response ->
            if (!response.isSuccessful) {
                Log.d(
                    TAG,
                    "BetterLyrics returned HTTP ${response.code} for: $title " +
                        "(album=${album?.takeIf(String::isNotBlank) ?: "missing"})"
                )
                return null
            }
            response.body.string()
        }
        val ttmlResponse = json.decodeFromString<BetterLyricsResponse>(body)
        val ttml = (ttmlResponse.ttml ?: ttmlResponse.lyrics)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val parsed = BetterLyricsTtmlParser.parseTTML(ttml) ?: return null
        return LyricsResult(plainLyric = parsed.plainLrc, rawLyric = parsed.enhancedLrc)
    }


    // endregion

    // region LrcLib (https://lrclib.net)

    @Serializable
    private data class LrcLibTrack(
        val id: Int = 0,
        val trackName: String = "",
        val artistName: String = "",
        val duration: Double = 0.0,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    private suspend fun fetchLrcLib(
        title: String,
        artist: String,
        duration: Int,
        album: String?
    ): String? {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        // Strategy 1: track_name + artist_name
        var tracks = queryLrcLib(
            trackName = cleanedTitle,
            artistName = cleanedArtist,
            albumName = album
        )
        var result = selectBestLrcLibTrack(tracks, duration)
        if (result != null) return result

        // Strategy 2: track_name only
        tracks = queryLrcLib(trackName = cleanedTitle)
        result = selectBestLrcLibTrack(tracks, duration)
        if (result != null) return result

        // Strategy 3: q = "artist title"
        tracks = queryLrcLib(query = "$cleanedArtist $cleanedTitle")
        result = selectBestLrcLibTrack(tracks, duration)
        if (result != null) return result

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
        } catch (error: Exception) {
            Log.d(TAG, "LrcLib query failed: ${error.message}")
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

        return synced.firstOrNull()?.syncedLyrics
            ?: withLyrics.firstOrNull()?.plainLyrics
    }

    // endregion

    // region KuGou (mobileservice.kugou.com + lyrics.kugou.com)

    @Serializable
    private data class KuGouSearchSongResponse(
        val data: KuGouSongData = KuGouSongData()
    )

    @Serializable
    private data class KuGouSongData(
        val info: List<KuGouSongInfo> = emptyList()
    )

    @Serializable
    private data class KuGouSongInfo(
        val hash: String = "",
        val duration: Int = 0
    )

    @Serializable
    private data class KuGouSearchLyricsResponse(
        val candidates: List<KuGouCandidate> = emptyList()
    )

    @Serializable
    private data class KuGouCandidate(
        val id: Long = 0,
        val accesskey: String = ""
    )

    @Serializable
    private data class KuGouDownloadResponse(
        val content: String = ""
    )

    private suspend fun fetchKuGou(
        title: String,
        artist: String,
        duration: Int,
        album: String?
    ): LyricsResult? {
        val normalizedTitle = title.replace(Regex("\\(.*\\)"), "").replace(Regex("（.*）"), "").trim()
        val normalizedArtist = artist.replace(", ", "、").replace(" & ", "、").trim()
        val keyword = buildString {
            append(normalizedTitle).append(" - ").append(normalizedArtist)
            album?.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
        }

        // Step 1: Search songs
        val searchUrl = buildString {
            append("https://mobileservice.kugou.com/api/v3/search/song?")
            append("version=9108&plat=0&pagesize=8&showtype=0&")
            append("keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        val searchResponse = executeJson<KuGouSearchSongResponse>(searchUrl) ?: return null

        // Step 2: Find matching song by duration and search lyrics by hash
        for (song in searchResponse.data.info) {
            if (duration <= 0 || abs(song.duration - duration) <= 8) {
                val lyricsUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${song.hash}"
                val lyricsResponse = executeJson<KuGouSearchLyricsResponse>(lyricsUrl)
                val candidate = lyricsResponse?.candidates?.firstOrNull()
                if (candidate != null) {
                    val lyrics = downloadKuGouLyrics(candidate.id, candidate.accesskey)
                    if (lyrics != null) return lyrics
                }
            }
        }

        // Step 3: Fallback - search lyrics by keyword
        val keywordLyricsUrl = buildString {
            append("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&")
            if (duration > 0) append("duration=${duration * 1000}&")
            append("keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        val keywordResponse = executeJson<KuGouSearchLyricsResponse>(keywordLyricsUrl)
        val candidate = keywordResponse?.candidates?.firstOrNull()
        if (candidate != null) {
            return downloadKuGouLyrics(candidate.id, candidate.accesskey)
        }

        return null
    }

    private suspend fun downloadKuGouLyrics(id: Long, accessKey: String): LyricsResult? {
        downloadKuGouContent(id, accessKey, "krc")
            ?.let(::convertEncryptedKrcToLyricsResult)
            ?.let { return it }

        val content = downloadKuGouContent(id, accessKey, "lrc") ?: return null
        return decodeLineLrc(content)?.let { LyricsResult(it, it) }
    }

    private suspend fun downloadKuGouContent(
        id: Long,
        accessKey: String,
        format: String
    ): String? {
        val url = "https://lyrics.kugou.com/download?fmt=$format&charset=utf8&client=pc" +
            "&ver=1&id=$id&accesskey=$accessKey"
        return executeJson<KuGouDownloadResponse>(url)
            ?.content
            ?.takeIf { it.isNotEmpty() }
    }

    internal fun convertEncryptedKrcToLyricsResult(base64Content: String): LyricsResult? {
        return runCatching {
            val encrypted = Base64.decode(base64Content, Base64.DEFAULT)
            val decrypted = KrcDecryptor.decrypt(encrypted) ?: return null
            convertDecryptedKrcToLyricsResult(decrypted)
        }.onFailure { error ->
            Log.d(TAG, "KuGou KRC decode failed: ${error.message}")
        }.getOrNull()
    }

    internal fun convertDecryptedKrcToLyricsResult(decrypted: String): LyricsResult? {
        val document = KrcParser.parse(decrypted)
        val lines = document.lines.filter { !it.text.isNullOrBlank() }
        if (lines.isEmpty()) return null

        val plain = buildString {
            lines.forEach { line ->
                append(BetterLyricsTtmlParser.formatLrcTime(line.begin))
                    .append(line.text.orEmpty().trim())
                    .append('\n')
            }
        }.trimEnd()
        val enhanced = buildString {
            lines.forEach { line ->
                append(BetterLyricsTtmlParser.formatLrcTime(line.begin))
                val words = line.words.orEmpty().filter { !it.text.isNullOrEmpty() }
                if (words.isEmpty()) {
                    append(line.text.orEmpty().trim())
                } else {
                    words.forEach { word ->
                        append('<')
                            .append(BetterLyricsTtmlParser.formatInlineTime(word.begin))
                            .append('>')
                        append(word.text.orEmpty())
                    }
                    val finalWord = words.last()
                    val finalEnd = finalWord.end.coerceAtLeast(finalWord.begin)
                    append('<')
                        .append(BetterLyricsTtmlParser.formatInlineTime(finalEnd))
                        .append('>')
                }
                append('\n')
            }
        }.trimEnd()
        return LyricsResult(plainLyric = plain, rawLyric = enhanced)
    }

    private fun decodeLineLrc(base64Content: String): String? {
        return runCatching {
            val decoded = String(Base64.decode(base64Content, Base64.DEFAULT), Charsets.UTF_8)
            decoded.lines()
                .filter { it.matches(Regex("\\[(\\d{1,3}):(\\d{2})\\.(\\d{2,3})].*")) }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // endregion

    // region Utilities

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
        } catch (error: Exception) {
            null
        }
    }

    /** Executes OkHttp asynchronously so track changes and provider timeouts cancel the call. */
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
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
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

    private fun LyricsResult.hasWordTiming(): Boolean {
        return INLINE_WORD_TIME_REGEX.containsMatchIn(rawLyric)
    }

    // endregion
}
