package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class AppleMusicLyricSource(private val client: OkHttpClient) : LyricsSource {
    override val name: String = "apple-music/lyric-api"
    private val appleIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private companion object {
        const val MATCH_THRESHOLD = 0.65
        /** Probe the top candidates so one lyric-less edition cannot deny the track. */
        const val PROBE_LIMIT = 2
    }

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val cacheKey = trackKey(query)
        val cached = appleIdCache[cacheKey]
        val ids = if (cached != null) listOf(cached) else searchAppleTrackIds(query)
        if (ids.isEmpty()) return null
        var instrumental: LyricResult? = null
        for (trackId in ids) {
            val result = fetchLyricForId(trackId, query) ?: continue
            if (result.lyric.isBlank()) continue
            if (SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)) {
                if (instrumental == null) instrumental = result
                continue
            }
            appleIdCache[cacheKey] = trackId
            return result
        }
        return instrumental
    }

    override fun searchCandidates(query: String): List<SearchCandidate> {
        if (query.isBlank()) return emptyList()
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "20")
            .build()
        return runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "Lyrico/UniversalProvider").build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val rows = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return@use emptyList()
                buildList {
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val id = row.optLong("trackId").takeIf { it > 0L }?.toString() ?: continue
                        add(
                            SearchCandidate(
                                id = id,
                                title = row.optString("trackName"),
                                artist = row.optString("artistName"),
                                album = row.optString("collectionName"),
                                source = "Apple Music",
                                coverUrl = row.optString("artworkUrl100").replace("100x100bb", "300x300bb").replace("100x100", "300x300"),
                                durationMs = row.optLong("trackTimeMillis")
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? {
        val query = TrackQuery(candidate.title, candidate.artist)
        appleIdCache[trackKey(query)] = candidate.id
        return fetchLyrics(query)
    }

    private fun trackKey(query: TrackQuery): String =
        query.title.trim().lowercase() + "|" + query.artist.trim().lowercase() + "|" + (query.durationMs ?: 0L)

    private fun fetchLyricForId(trackId: String, query: TrackQuery): LyricResult? {
        val url = "https://lyrics.paxsenix.org/apple-music/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("id", trackId)
            .addQueryParameter("ttml", "false")
            .build()
        val started = System.currentTimeMillis()
        client.newCall(
            Request.Builder().url(url).header("User-Agent", "Lyrico/UniversalProvider").header("accept", "application/json").build()
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            UniversalDiagnostics.networkCall(
                source = name,
                op = "lyric-api",
                generation = query.generation,
                httpCode = response.code,
                durationMs = System.currentTimeMillis() - started,
                bodyChars = body.length,
                parseOk = response.isSuccessful && body.isNotBlank()
            )
            if (!response.isSuccessful || body.isBlank()) return null
            val original = parseLyricApi(JSONObject(body))
            if (original.isEmpty()) return null
            val lineLyric = StructuredLyrics.toPlainLrc(original)
            val raw = StructuredLyrics.toEnhancedLrc(original)
            UniversalDiagnostics.lyricPayload(
                source = name,
                generation = query.generation,
                lyricChars = lineLyric.length,
                rawChars = raw.length,
                hasWordTiming = StructuredLyrics.hasWordTiming(original),
                hasTranslation = false,
                converted = StructuredLyrics.hasWordTiming(original)
            )
            return LyricResult(
                title = query.title,
                artist = query.artist,
                lyric = lineLyric,
                rawLyric = raw,
                translationLyric = null,
                source = "universal/apple-music",
                sourceTrackId = trackId
            )
        }
    }

    private fun parseLyricApi(root: JSONObject): List<TimedLine> {
        val fromContent = parseContent(root.optJSONArray("content"))
        if (fromContent.isNotEmpty()) return fromContent
        val elrc = root.optString("elrc").ifBlank { root.optString("elrcMultiPerson") }
        val fromElrc = StructuredLyrics.parseLrc(elrc)
        if (fromElrc.isNotEmpty()) return fromElrc
        return StructuredLyrics.parseLrc(root.optString("lrc"))
    }

    private fun parseContent(content: JSONArray?): List<TimedLine> {
        if (content == null || content.length() == 0) return emptyList()
        val lines = mutableListOf<TimedLine>()
        for (i in 0 until content.length()) {
            val line = content.optJSONObject(i) ?: continue
            val start = line.optLong("timestamp")
            val end = line.optLong("endtime").takeIf { it > start } ?: (start + 3000L)
            val textArray = line.optJSONArray("text")
            val words = mutableListOf<TimedWord>()
            if (textArray != null) {
                for (j in 0 until textArray.length()) {
                    val word = textArray.optJSONObject(j) ?: continue
                    val text = word.optString("text")
                    if (text.isBlank()) continue
                    val wordStart = word.optLong("timestamp").takeIf { it > 0L } ?: start
                    val wordEnd = word.optLong("endtime").takeIf { it > wordStart } ?: end
                    words += TimedWord(wordStart, wordEnd, text)
                }
            }
            if (words.isEmpty()) {
                val plain = line.optString("plain").ifBlank { if (textArray == null) line.optString("text") else "" }
                if (plain.isBlank()) continue
                words += TimedWord(start, end, plain)
            }
            lines += TimedLine(start, end, words)
        }
        return lines.sortedBy { it.startMs }
    }

    private fun searchAppleTrackIds(query: TrackQuery): List<String> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", (query.title + " " + query.artist).trim())
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "20")
            .build()
        val started = System.currentTimeMillis()
        client.newCall(Request.Builder().url(url).header("User-Agent", "Lyrico/UniversalProvider").build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            UniversalDiagnostics.networkCall(
                source = name,
                op = "search",
                generation = query.generation,
                httpCode = response.code,
                durationMs = System.currentTimeMillis() - started,
                bodyChars = body.length,
                parseOk = response.isSuccessful && body.isNotBlank()
            )
            if (!response.isSuccessful) return emptyList()
            val rows = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val scored = mutableListOf<Pair<String, Double>>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optLong("trackId").takeIf { it > 0L }?.toString() ?: continue
                val score = SongMatchScorer.calculate(
                    query.title,
                    query.artist,
                    query.durationMs,
                    row.optString("trackName"),
                    row.optString("artistName"),
                    row.optLong("trackTimeMillis").takeIf { it > 0L },
                    rankIndex = index
                )
                scored += id to score
            }
            val best = scored.maxOfOrNull { it.second } ?: -1.0
            UniversalDiagnostics.sourceMatch(
                source = name,
                generation = query.generation,
                candidateCount = rows.length(),
                bestScore = best,
                threshold = MATCH_THRESHOLD,
                accepted = best >= MATCH_THRESHOLD,
                keywordMode = "title+artist",
                durationPresent = query.durationMs != null && query.durationMs > 0L
            )
            if (best < MATCH_THRESHOLD) return emptyList()
            val probeFloor = maxOf(MATCH_THRESHOLD, best - 0.15)
            return scored.sortedByDescending { it.second }
                .filter { it.second >= probeFloor }
                .take(PROBE_LIMIT)
                .map { it.first }
        }
    }
}
