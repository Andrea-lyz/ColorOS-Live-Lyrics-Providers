package io.github.andrealtb.coloroslyrics.provider.universal.api

import android.util.Base64
import io.github.andrealtb.coloroslyrics.provider.parser.krc.KrcDecryptor
import io.github.andrealtb.coloroslyrics.provider.parser.krc.KrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.krc.language.LanguageParser
import io.github.andrealtb.coloroslyrics.provider.parser.krc.language.LyricSection
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Kugou Music lyrics (word-timed KRC with an optional translation section).
 *
 * The request shapes follow the Lyrico Kugou plugin (Replica0110/Lyrico-Plugins): the Android
 * client search signed with the public client salt, then the lyric search by file hash and the
 * KRC download. Romanization sections are ignored by contract.
 */
class KugouSource(private val client: OkHttpClient) : LyricsSource {
    override val name: String = "kugou"

    private companion object {
        const val MATCH_THRESHOLD = 0.45
        const val CONFIDENT_MATCH_THRESHOLD = 0.75
        const val PROBE_SCORE_SLACK = 0.15
        const val PROBE_LIMIT = 3
        const val SEARCH_PAGE_SIZE = 10
        const val SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        const val APP_ID = "3116"
        const val CLIENT_VERSION = "11070"
        const val USER_AGENT = "Android14-1070-11070-201-0-SearchSong-wifi"
        val KRC_LINE = Regex("""^\[(\d+)\s*,\s*(\d+)]""")
    }

    private val deviceMid: String = md5(System.nanoTime().toString())

    /** One search row; [id] is Kugou's album_audio_id and [hash] the file hash lyrics key on. */
    internal data class Song(
        val id: String,
        val hash: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String
    )

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val ranked = search(query)
        var instrumental: LyricResult? = null
        for (song in ranked) {
            val result = fetch(song, query) ?: continue
            if (result.lyric.isBlank()) continue
            if (SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)) {
                if (instrumental == null) instrumental = result
                continue
            }
            return result
        }
        return instrumental
    }

    private fun search(query: TrackQuery): List<Song> {
        val core = SongMatchScorer.coreTitle(query.title)
        val attempts = buildList {
            add("title+artist" to "${query.title} ${query.artist}".trim())
            if (core.isNotBlank() && !core.equals(query.title.trim(), ignoreCase = true)) {
                add("core+artist" to "$core ${query.artist}".trim())
            }
            add("title" to query.title.trim())
        }
        var bestScore = -1.0
        var bestCount = 0
        var bestMode = "title+artist"
        var ranked: List<Pair<Song, Double>> = emptyList()
        for ((mode, keyword) in attempts) {
            if (keyword.isBlank()) continue
            val songs = runCatching { searchSongs(query.generation, keyword, mode) }.getOrNull() ?: continue
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
            val localBest = scored.maxOfOrNull { it.second } ?: -1.0
            if (localBest > bestScore) {
                bestScore = localBest
                bestCount = songs.size
                bestMode = mode
                ranked = scored.sortedByDescending { it.second }
            }
            if (bestScore >= CONFIDENT_MATCH_THRESHOLD) break
        }
        UniversalDiagnostics.sourceMatch(
            source = name,
            generation = query.generation,
            candidateCount = bestCount,
            bestScore = bestScore,
            threshold = MATCH_THRESHOLD,
            accepted = bestScore >= MATCH_THRESHOLD,
            keywordMode = bestMode,
            durationPresent = query.durationMs != null && query.durationMs > 0L
        )
        if (bestScore < MATCH_THRESHOLD) return emptyList()
        val probeFloor = maxOf(MATCH_THRESHOLD, bestScore - PROBE_SCORE_SLACK)
        return ranked.filter { it.second >= probeFloor }.take(PROBE_LIMIT).map { it.first }
    }

    private fun searchSongs(generation: Long, keyword: String, op: String): List<Song> {
        val params = linkedMapOf(
            "userid" to "0",
            "appid" to APP_ID,
            "token" to "",
            "clienttime" to (System.currentTimeMillis() / 1000L).toString(),
            "iscorrection" to "1",
            "uuid" to "-",
            "mid" to deviceMid,
            "dfid" to "-",
            "clientver" to CLIENT_VERSION,
            "platform" to "AndroidFilter",
            "keyword" to keyword,
            "page" to "1",
            "pagesize" to SEARCH_PAGE_SIZE.toString()
        )
        val root = getJson(
            generation,
            "search-$op",
            "https://complexsearch.kugou.com/v2/search/song?" + signedQuery(params),
            mapOf("x-router" to "complexsearch.kugou.com")
        ) ?: return emptyList()
        if (root.optInt("error_code", 0) != 0) return emptyList()
        return KugouSearchResponseParser.songs(root.optJSONObject("data")?.optJSONArray("lists"))
    }

    private fun fetch(song: Song, query: TrackQuery): LyricResult? {
        if (song.hash.isBlank()) return null
        val searchParams = linkedMapOf(
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "album_audio_id" to song.id,
            "duration" to song.durationMs.toString(),
            "hash" to song.hash,
            "keyword" to song.artist + " - " + song.title,
            "lrctxt" to "1",
            "man" to "no"
        )
        val candidates = getJson(
            query.generation,
            "lyric-search",
            "https://lyrics.kugou.com/v1/search?" + signedQuery(searchParams),
            emptyMap()
        )?.optJSONArray("candidates")
        val candidate = candidates?.optJSONObject(0)
        if (candidate == null) {
            UniversalDiagnostics.sourceEmpty(name, query.generation, 0L, "lyricCandidateMissing")
            return null
        }
        val downloadParams = linkedMapOf(
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "accesskey" to candidate.optString("accesskey"),
            "charset" to "utf8",
            "client" to "mobi",
            "fmt" to "krc",
            "id" to candidate.optString("id"),
            "ver" to "1"
        )
        val download = getJson(
            query.generation,
            "lyric-download",
            "https://lyrics.kugou.com/download?" + signedQuery(downloadParams),
            emptyMap()
        ) ?: return null
        val content = download.optString("content")
        if (content.isBlank()) return null
        val bytes = runCatching { Base64.decode(content, Base64.DEFAULT) }.getOrNull() ?: return null
        val text = if (download.optInt("contenttype") == 2) {
            String(bytes, Charsets.UTF_8)
        } else {
            KrcDecryptor.decrypt(bytes)
        }
        return text?.let { toResult(it, query, song) }
    }

    internal fun toResult(text: String, query: TrackQuery, song: Song): LyricResult? {
        val document = KrcParser.parse(text)
        val original = document.lines.mapNotNull { line ->
            val words = line.words.orEmpty()
                .filter { !it.text.isNullOrEmpty() }
                .map { TimedWord(it.begin, it.end.coerceAtLeast(it.begin), it.text.orEmpty()) }
            val lineText = line.text.orEmpty()
            when {
                words.isNotEmpty() -> TimedLine(line.begin, line.end, words)
                lineText.isNotBlank() -> TimedLine(line.begin, line.end, listOf(TimedWord(line.begin, line.end, lineText)))
                else -> null
            }
        }.filter { it.text.isNotBlank() }
        if (original.isEmpty()) {
            UniversalDiagnostics.sourceEmpty(name, query.generation, 0L, "structuredEmpty krcChars=" + text.length)
            return null
        }
        val translation = if (Locale.getDefault().language == "zh") {
            val lines = translationLines(text, document.language)
            StructuredLyrics.toTranslationLrc(StructuredLyrics.merge(original, lines)).takeIf { it.isNotBlank() }
        } else {
            null
        }
        val lineLyric = StructuredLyrics.toPlainLrc(original)
        val raw = StructuredLyrics.toEnhancedLrc(original)
        UniversalDiagnostics.lyricPayload(
            source = name,
            generation = query.generation,
            lyricChars = lineLyric.length,
            rawChars = raw.length,
            hasWordTiming = StructuredLyrics.hasWordTiming(original),
            hasTranslation = !translation.isNullOrBlank(),
            converted = StructuredLyrics.hasWordTiming(original)
        )
        return LyricResult(
            title = query.title,
            artist = query.artist,
            lyric = lineLyric,
            rawLyric = raw,
            translationLyric = translation,
            source = "universal/kugou",
            sourceTrackId = song.hash,
            format = if (StructuredLyrics.hasWordTiming(original)) LyricFormats.KRC else LyricFormats.LRC
        )
    }

    /**
     * The translation section lists one entry per KRC line in document order, including lines
     * without text, so it is paired with every `[start,duration]` row rather than the parsed
     * lines (which drop the empty ones).
     */
    private fun translationLines(text: String, language: String?): List<TimedLine> {
        val entries = language?.let(LanguageParser::parse)?.content
            ?.firstOrNull { it.type == LyricSection.TYPE_TRANSLATE }
            ?.flatten()
            ?: return emptyList()
        val rows = text.lineSequence()
            .mapNotNull { KRC_LINE.find(it.trim()) }
            .map { match -> match.groupValues[1].toLong() to match.groupValues[2].toLong() }
            .toList()
        return rows.mapIndexedNotNull { index, (start, duration) ->
            val value = entries.getOrNull(index)?.trim().orEmpty()
            if (value.isEmpty()) null
            else TimedLine(start, start + duration, listOf(TimedWord(start, start + duration, value)))
        }
    }

    override fun searchCandidates(query: String): List<SearchCandidate> = runCatching {
        searchSongs(0L, query.trim(), "manual").map { song ->
            SearchCandidate(
                id = song.id + "|" + song.hash,
                title = song.title,
                artist = song.artist,
                album = song.album,
                source = LyricsSourceAggregator.SourceId.KUGOU.label,
                coverUrl = song.coverUrl,
                durationMs = song.durationMs
            )
        }
    }.getOrDefault(emptyList())

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? {
        val id = candidate.id.substringBefore('|')
        val hash = candidate.id.substringAfter('|', "")
        if (hash.isBlank()) return null
        val song = Song(id, hash, candidate.title, candidate.artist, candidate.album, candidate.durationMs, candidate.coverUrl)
        return fetch(song, TrackQuery(candidate.title, candidate.artist, candidate.album, candidate.durationMs))
    }

    /** Kugou signs the sorted `key=value` pairs, concatenated without separators, inside the salt. */
    internal fun signedQuery(params: Map<String, String>): String {
        val sorted = params.toSortedMap()
        val signature = md5(SALT + sorted.entries.joinToString("") { it.key + "=" + it.value } + SALT)
        return (sorted + ("signature" to signature)).entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
    }

    private fun getJson(generation: Long, op: String, url: String, headers: Map<String, String>): JSONObject? {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val started = System.currentTimeMillis()
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            UniversalDiagnostics.networkCall(
                source = name,
                op = op,
                generation = generation,
                httpCode = response.code,
                durationMs = System.currentTimeMillis() - started,
                bodyChars = body.length,
                parseOk = response.isSuccessful && body.isNotBlank()
            )
            if (!response.isSuccessful) throw IOException("kugou $op http=" + response.code)
            if (body.isBlank()) return null
            return JSONObject(body)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

internal object KugouSearchResponseParser {
    /** Search rows plus the other versions Kugou nests under `Grp`, without duplicates. */
    fun songs(lists: JSONArray?): List<KugouSource.Song> {
        if (lists == null) return emptyList()
        val pending = ArrayDeque<JSONObject>()
        for (i in 0 until lists.length()) lists.optJSONObject(i)?.let(pending::addLast)
        val seen = HashSet<String>()
        val songs = mutableListOf<KugouSource.Song>()
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            val group = item.optJSONArray("Grp") ?: item.optJSONArray("group")
            val song = song(item)
            if (song != null && seen.add(song.id + "|" + song.hash)) songs += song
            if (group != null) {
                for (i in 0 until group.length()) group.optJSONObject(i)?.let(pending::addLast)
            }
        }
        return songs
    }

    private fun song(item: JSONObject): KugouSource.Song? {
        val title = item.optString("SongName").trim()
        val hash = item.optString("FileHash").trim()
        if (title.isEmpty() || hash.isEmpty()) return null
        val singers = item.optJSONArray("Singers")
        val artist = buildList {
            if (singers != null) {
                for (i in 0 until singers.length()) {
                    val singer = singers.optJSONObject(i) ?: continue
                    val name = singer.optString("name").ifBlank { singer.optString("Name") }.trim()
                    if (name.isNotEmpty()) add(name)
                }
            }
        }.joinToString("/").ifBlank { item.optString("SingerName").trim() }
        return KugouSource.Song(
            id = item.optString("ID").ifBlank { item.optString("MixSongID") },
            hash = hash,
            title = title,
            artist = artist,
            album = item.optString("AlbumName"),
            durationMs = item.optLong("Duration") * 1000L,
            coverUrl = item.optString("Image").replace("{size}", "480").replace("http:", "https:")
        )
    }
}
