package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Apple Music lyrics.
 *
 * The track is matched in the iTunes catalogue first, which keeps the match threshold and stops
 * a wrong song from being shown. The syllable-timed Apple TTML (with its duet singers) then comes
 * from the Better Lyrics API, which answers from its cache without a key; a song it has not
 * cached returns 401 and is a plain miss. The paxsenix JSON API stays as a fallback for when it
 * recovers: its lyric origin answered 502 for every track on 2026-11-17.
 */
class AppleMusicLyricSource(private val client: OkHttpClient) : LyricsSource {
    override val name: String = "apple-music/lyric-api"
    override val deliversTtml: Boolean = true
    private val appleIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val official = AppleMusicOfficialClient(client)

    private companion object {
        const val MATCH_THRESHOLD = 0.65
        /** Probe the top candidates so one lyric-less edition cannot deny the track. */
        const val PROBE_LIMIT = 2
        const val BETTER_LYRICS_URL = "https://api.betterlyrics.org/getLyrics"
        const val USER_AGENT = "ColorOSLiveLyrics-UniversalProvider"
        /** The API rate-limits per IP, so one track never costs more lookups than this. */
        const val BETTER_LYRICS_ATTEMPTS = 2
        const val OFFICIAL_ID_PREFIX = "official:"
        val ARTIST_SEPARATOR = Regex(
            """\s*(?:/|&|,|、|;|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        if (AppleMusicAuthState.current.usable) {
            fetchOfficial(query)?.let { return it }
        }
        return fetchFree(query)
    }

    /** Better Lyrics, then paxsenix: the paths that need no Apple Music account. */
    private fun fetchFree(query: TrackQuery): LyricResult? {
        val cacheKey = trackKey(query)
        val cached = appleIdCache[cacheKey]
        val matches = if (cached != null) {
            listOf(AppleMatch(cached, null, null, null))
        } else {
            searchAppleTracks(query)
        }
        if (matches.isEmpty()) return null
        fetchBetterLyrics(query, matches.first())?.let { result ->
            appleIdCache[cacheKey] = matches.first().id
            return result
        }
        var instrumental: LyricResult? = null
        for (match in matches) {
            val trackId = match.id
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
        if (AppleMusicAuthState.current.usable) {
            // Catalogue ids belong to a storefront, so official rows come from the account's own.
            val rows = runCatching { official.search(query.trim(), 20, 0L) }.getOrDefault(emptyList())
            if (rows.isNotEmpty()) {
                return rows.map { song ->
                    SearchCandidate(
                        id = OFFICIAL_ID_PREFIX + song.id,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        source = "Apple Music",
                        coverUrl = song.coverUrl,
                        durationMs = song.durationMs,
                        formatHint = if (song.hasSyncedLyrics) LyricFormats.TTML else ""
                    )
                }
            }
        }
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
        if (candidate.id.startsWith(OFFICIAL_ID_PREFIX)) {
            val query = TrackQuery(candidate.title, candidate.artist, candidate.album, candidate.durationMs.takeIf { it > 0L })
            val songId = candidate.id.removePrefix(OFFICIAL_ID_PREFIX)
            officialLyrics(songId, query, expectSynced = candidate.formatHint == LyricFormats.TTML)?.let { return it }
            // Without a subscription Apple serves no lyrics; catalogue song ids are the iTunes
            // track ids, so the free path can still look the same song up.
            appleIdCache[trackKey(query)] = songId
            return fetchFree(query)
        }
        val query = TrackQuery(
            candidate.title,
            candidate.artist,
            candidate.album,
            candidate.durationMs.takeIf { it > 0L }
        )
        appleIdCache[trackKey(query)] = candidate.id
        return fetchLyrics(query)
    }

    /**
     * Official catalogue path: search the account storefront, then read the song's own TTML.
     * A refused account token marks the credentials and lets the free path answer instead.
     */
    private fun fetchOfficial(query: TrackQuery): LyricResult? {
        val songs = runCatching {
            official.search((query.title + " " + query.artist).trim(), 10, query.generation)
        }.getOrDefault(emptyList())
        val scored = songs.mapIndexed { index, song ->
            song to SongMatchScorer.calculate(
                query.title,
                query.artist,
                query.durationMs,
                song.title,
                song.artist,
                song.durationMs.takeIf { it > 0L },
                rankIndex = index
            )
        }
        val best = scored.maxOfOrNull { it.second } ?: -1.0
        UniversalDiagnostics.sourceMatch(
            source = "apple-music/official",
            generation = query.generation,
            candidateCount = songs.size,
            bestScore = best,
            threshold = MATCH_THRESHOLD,
            accepted = best >= MATCH_THRESHOLD,
            keywordMode = "title+artist",
            durationPresent = query.durationMs != null && query.durationMs > 0L
        )
        if (best < MATCH_THRESHOLD) return null
        val probeFloor = maxOf(MATCH_THRESHOLD, best - 0.15)
        val candidates = scored.filter { it.second >= probeFloor }
            .sortedWith(compareByDescending<Pair<AppleMusicOfficialClient.Song, Double>> { it.first.hasSyncedLyrics }.thenByDescending { it.second })
            .take(PROBE_LIMIT)
        for ((song, _) in candidates) {
            officialLyrics(song.id, query, song.hasSyncedLyrics)?.let { return it }
            if (!AppleMusicAuthState.current.usable) break
        }
        return null
    }

    /**
     * [expectSynced]: the catalogue said this song has synced lyrics, so an answer without a
     * document means Apple withheld them from this account rather than the song lacking them.
     */
    private fun officialLyrics(songId: String, query: TrackQuery, expectSynced: Boolean): LyricResult? {
        val answer = try {
            official.lyricsTtml(songId, query.generation)
        } catch (_: AppleMusicOfficialClient.Rejected) {
            classifyRefusal(query.generation, "lyricsRefused")
            return null
        } ?: return null
        val document = answer.ttml
        if (document == null) {
            // Without a subscription Apple still lists the lyric entries but leaves out their TTML
            // (seen 2026-11-17: HTTP 200, entries present, no document).
            if (expectSynced) classifyRefusal(query.generation, if (answer.hadLyricData) "lyricsEmpty" else "lyricsWithheld")
            return null
        }
        val result = TtmlLyrics.toResult(document, query, "universal/apple-music", songId) ?: return null
        if (AppleMusicAuthState.current.status != AppleMusicAuth.STATUS_OK) {
            AppleMusicAuthState.update { it.copy(status = AppleMusicAuth.STATUS_OK) }
        }
        return result
    }

    /**
     * Apple refuses or withholds lyrics both for an expired token and for an account without a
     * subscription. The account endpoint tells them apart: it still answers a valid token.
     */
    private fun classifyRefusal(generation: Long, reason: String) {
        val token = AppleMusicAuthState.current.mediaUserToken
        if (token.isBlank()) return
        val status = try {
            if (official.accountStorefront(token) != null) AppleMusicAuth.STATUS_NO_SUBSCRIPTION else null
        } catch (_: AppleMusicOfficialClient.Rejected) {
            AppleMusicAuth.STATUS_REJECTED
        } catch (_: Exception) {
            null
        } ?: return
        AppleMusicAuthState.update { it.copy(status = status) }
        UniversalDiagnostics.sourceEmpty("apple-music/official", generation, 0L, "$reason status=$status")
    }

    private data class AppleMatch(
        val id: String,
        val title: String?,
        val artist: String?,
        val durationMs: Long?
    )

    /**
     * Better Lyrics keys its cache on title, artist and duration (case-insensitive, with a second
     * or two of tolerance). An album makes the same song miss, so it is never sent.
     */
    private fun fetchBetterLyrics(query: TrackQuery, match: AppleMatch): LyricResult? {
        val durationMs = (query.durationMs ?: match.durationMs)?.takeIf { it > 0L } ?: return null
        val attempts = listOf(
            query.title.trim() to query.artist.trim(),
            (match.title ?: query.title).trim() to (match.artist ?: query.artist).trim(),
            query.title.trim() to query.artist.split(ARTIST_SEPARATOR).first().trim()
        ).filter { (title, artist) -> title.isNotBlank() && artist.isNotBlank() }
            .distinctBy { (title, artist) -> title.lowercase() + "|" + artist.lowercase() }
            .take(BETTER_LYRICS_ATTEMPTS)
        for ((title, artist) in attempts) {
            val url = BETTER_LYRICS_URL.toHttpUrl().newBuilder()
                .addQueryParameter("s", title)
                .addQueryParameter("a", artist)
                .addQueryParameter("d", ((durationMs + 500L) / 1000L).toString())
                .build()
            val started = System.currentTimeMillis()
            var rateLimited = false
            val document = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                UniversalDiagnostics.networkCall(
                    source = name,
                    op = "better-lyrics",
                    generation = query.generation,
                    httpCode = response.code,
                    durationMs = System.currentTimeMillis() - started,
                    bodyChars = body.length,
                    parseOk = response.isSuccessful && body.isNotBlank(),
                    extra = "cache=" + (response.header("X-Cache-Status") ?: "unknown")
                )
                // 401 is an uncached song and 404 an unknown one: a miss, not a failure. 429
                // means the per-IP budget is spent, so no further lookup is made for this track.
                rateLimited = response.code == 429
                if (!response.isSuccessful || body.isBlank()) {
                    null
                } else {
                    runCatching { JSONObject(body).optString("ttml") }.getOrNull()
                }
            }
            if (rateLimited) return null
            val result = TtmlLyrics.toResult(document, query, "universal/apple-music", match.id) ?: continue
            UniversalDiagnostics.lyricPayload(
                source = name,
                generation = query.generation,
                lyricChars = result.lyric.length,
                rawChars = result.rawLyric.orEmpty().length,
                hasWordTiming = WordTimedLyricConverter.hasRealWordTiming(result.rawLyric.orEmpty()),
                hasTranslation = !result.translationLyric.isNullOrBlank(),
                converted = false
            )
            return result
        }
        return null
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
                sourceTrackId = trackId,
                format = if (StructuredLyrics.hasWordTiming(original)) LyricFormats.ENHANCED_LRC else LyricFormats.LRC
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

    private fun searchAppleTracks(query: TrackQuery): List<AppleMatch> {
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
            val scored = mutableListOf<Pair<AppleMatch, Double>>()
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
                val match = AppleMatch(
                    id = id,
                    title = row.optString("trackName").takeIf { it.isNotBlank() },
                    artist = row.optString("artistName").takeIf { it.isNotBlank() },
                    durationMs = row.optLong("trackTimeMillis").takeIf { it > 0L }
                )
                scored += match to score
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
