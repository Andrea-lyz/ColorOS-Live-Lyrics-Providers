package io.github.andrealtb.coloroslyrics.provider.universal.api

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import io.github.andrealtb.coloroslyrics.provider.parser.qrc.decrypt.QrcDecrypter
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics
import org.json.JSONArray
import java.io.IOException

class QQMusicSource(private val client: OkHttpClient) : LyricsSource {
    override val name: String = "qq"

    private companion object {
        const val MATCH_THRESHOLD = 0.45
        const val CONFIDENT_MATCH_THRESHOLD = 0.75
        const val PROBE_SCORE_SLACK = 0.15
        /** The top candidates are probed in order, so one instrumental upload cannot deny the track. */
        const val PROBE_LIMIT = 3
    }

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val ranked = search(query)
        if (ranked.isEmpty()) return null
        var instrumental: LyricResult? = null
        for (song in ranked) {
            val songMid = QqSearchResponseParser.mid(song)
            if (songMid.isBlank()) continue
            val songId = QqSearchResponseParser.songId(song)
            val result = fetch(songMid, songId, query) ?: continue
            if (result.lyric.isBlank()) continue
            if (SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)) {
                if (instrumental == null) instrumental = result
                continue
            }
            return result
        }
        // A genuinely instrumental track still reports its nature to the caller.
        return instrumental
    }

    /** Scored candidates, best first, bounded by [PROBE_LIMIT]. */
    private fun search(query: TrackQuery): List<JSONObject> {
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
        var ranked: List<Pair<JSONObject, Double>> = emptyList()

        for ((mode, keyword) in attempts) {
            if (keyword.isBlank()) continue
            val songs = searchSongs(query.generation, keyword, mode) ?: continue
            val scored = mutableListOf<Pair<JSONObject, Double>>()
            for (i in 0 until songs.length()) {
                val item = songs.optJSONObject(i) ?: continue
                val score = SongMatchScorer.calculate(
                    query.title,
                    query.artist,
                    query.durationMs,
                    QqSearchResponseParser.title(item),
                    QqSearchResponseParser.artist(item),
                    QqSearchResponseParser.durationMs(item).takeIf { it > 0L },
                    rankIndex = i
                )
                scored += item to score
            }
            val localBest = scored.maxOfOrNull { it.second } ?: -1.0
            if (localBest > bestScore) {
                bestScore = localBest
                bestCount = songs.length()
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

    /**
     * QQ Music answers from several endpoints and only some of them survive every network path.
     * Each attempt is isolated, so a timeout on one payload still lets the next one run; before
     * this a single exception aborted the search and the combined list silently lost QQ rows.
     */
    private fun searchSongs(generation: Long, keyword: String, mode: String): JSONArray? {
        val strategies = listOf<Pair<String, () -> JSONArray?>>(
            "desktop" to { searchSongsDesktop(generation, keyword, mode) },
            "lite-post" to { searchSongsLitePost(generation, keyword, mode) },
            "lite-get" to { searchSongsLiteGet(generation, keyword, mode) }
        )
        for ((label, call) in strategies) {
            val songs = runCatching { call() }.getOrNull()
            if (songs != null && songs.length() > 0) {
                if (label != "desktop") {
                    UniversalDiagnostics.networkCall(
                        source = name,
                        op = "search-" + mode + "-fallback",
                        generation = generation,
                        httpCode = 200,
                        durationMs = 0L,
                        bodyChars = 0,
                        parseOk = true,
                        extra = "strategy=" + label + " songs=" + songs.length()
                    )
                }
                return songs
            }
        }
        UniversalDiagnostics.sourceEmpty(name, generation, 0L, "searchExhausted keywordMode=" + mode)
        return null
    }

    private fun searchSongsDesktop(generation: Long, keyword: String, mode: String): JSONArray? {
        val payload = JSONObject().apply {
            put("comm", desktopComm())
            put("req", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                put("param", JSONObject().apply {
                    put("search_id", System.currentTimeMillis().toString())
                    put("query", keyword)
                    put("search_type", 0)
                    put("num_per_page", 10)
                    put("page_num", 1)
                    put("grp", 1)
                })
            })
        }
        val json = postJson(generation, "search-desktop-" + mode, payload) ?: return null
        return QqSearchResponseParser.songArray(json)
    }

    private fun searchSongsLitePost(generation: Long, keyword: String, mode: String): JSONArray? {
        val payload = JSONObject().apply {
            put("comm", qqComm())
            put("req_0", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicLite")
                put("param", JSONObject().apply {
                    put("search_id", System.currentTimeMillis().toString())
                    put("remoteplace", "search.android.keyboard")
                    put("query", keyword)
                    put("search_type", 0)
                    put("num_per_page", 10)
                    put("page_num", 1)
                    put("highlight", 0)
                    put("nqc_flag", 0)
                    put("page_id", 1)
                    put("grp", 1)
                })
            })
        }
        val json = postJson(generation, "search-lite-" + mode, payload) ?: return null
        return QqSearchResponseParser.songArray(json)
    }

    private fun searchSongsLiteGet(generation: Long, keyword: String, mode: String): JSONArray? {
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            })
            put("req", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicLite")
                put("param", JSONObject().apply {
                    put("search_type", 0)
                    put("query", keyword)
                    put("page_num", 1)
                    put("num_per_page", 10)
                })
            })
        }
        val encodedData = java.net.URLEncoder.encode(payload.toString(), "UTF-8")
        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" + encodedData)
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .addHeader("Referer", "https://y.qq.com/")
            .build()
        val started = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("qq lite http=" + response.code)
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            val songs = QqSearchResponseParser.songArray(json)
            UniversalDiagnostics.networkCall(
                source = name,
                op = "search-lite-get-" + mode,
                generation = generation,
                httpCode = response.code,
                durationMs = System.currentTimeMillis() - started,
                bodyChars = body.length,
                parseOk = songs.length() > 0,
                extra = "songs=" + songs.length()
            )
            return songs
        }
    }

    private fun fetch(songMid: String, songId: Long, query: TrackQuery): LyricResult? {
        // The mobile client comm is refused on some networks; the desktop comm still answers.
        val json = requestLyric(songMid, songId, query, qqComm())
            ?: requestLyric(songMid, songId, query, desktopComm())
            ?: return null
        val data = json.optJSONObject("req_0")?.optJSONObject("data")
            ?: json.optJSONObject("req")?.optJSONObject("data")
        if (data == null) {
            UniversalDiagnostics.sourceEmpty(name, query.generation, 0L, "lyricDataMissing")
            return null
        }

        val qrcText = decodeQqLyricPayload(data.optString("lyric").ifBlank { data.optString("qrc") })
        val transText = decodeQqLyricPayload(data.optString("trans"))
        val original = StructuredLyrics.parseQrc(qrcText).ifEmpty { StructuredLyrics.parseLrc(qrcText) }
        if (original.isEmpty()) {
            UniversalDiagnostics.sourceEmpty(name, query.generation, 0L, "structuredEmpty qrcChars=" + qrcText.length)
            return null
        }

        val isChineseSystem = Locale.getDefault().language == "zh"
        val transLines = StructuredLyrics.parseLrc(transText).ifEmpty { StructuredLyrics.parseQrc(transText) }
        val merged = if (isChineseSystem) StructuredLyrics.merge(original, transLines) else emptyList()
        val lineLyric = StructuredLyrics.toPlainLrc(original)
        val raw = StructuredLyrics.toEnhancedLrc(original)
        val translation = StructuredLyrics.toTranslationLrc(merged).takeIf { it.isNotBlank() }
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
            source = "universal/qq",
            sourceTrackId = songMid
        )
    }

    private fun requestLyric(
        songMid: String,
        songId: Long,
        query: TrackQuery,
        comm: JSONObject
    ): JSONObject? {
        val payload = JSONObject().apply {
            put("comm", comm)
            put("req_0", JSONObject().apply {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                put("param", JSONObject().apply {
                    put("songMID", songMid)
                    put("songID", songId)
                    put("songName", Base64.encodeToString(query.title.toByteArray(), Base64.NO_WRAP))
                    put("crypt", 1)
                    put("qrc", 1)
                    put("roma", 0)
                    put("trans", 1)
                    put("cv", 2111)
                    put("ct", 19)
                    put("lrc_t", 0)
                    put("qrc_t", 0)
                    put("roma_t", 0)
                    put("trans_t", 0)
                    put("type", 0)
                })
            })
        }
        return runCatching { postJson(query.generation, "lyric", payload) }.getOrNull()
    }

    private fun postJson(generation: Long, op: String, payload: JSONObject): JSONObject? {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(payload.toString().toRequestBody(mediaType))
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0")
            .addHeader("Referer", "https://y.qq.com/n/ryqq/search")
            .addHeader("Origin", "https://y.qq.com")
            .addHeader("Accept", "application/json, text/plain, */*")
            .build()
        val started = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
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
            if (!response.isSuccessful) {
                throw IOException("qq " + op + " http=" + response.code)
            }
            if (body.isBlank()) return null
            return JSONObject(body)
        }
    }

    private fun decodeBase64OrSelf(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    private fun qqComm(): JSONObject = JSONObject().apply {
        put("ct", "11")
        put("cv", "1003006")
        put("v", "1003006")
        put("os_ver", "15")
        put("phonetype", "24122RKC7C")
        put("tmeAppID", "qqmusiclight")
        put("nettype", "NETWORK_WIFI")
    }

    private fun desktopComm(): JSONObject = JSONObject().apply {
        put("ct", "19")
        put("cv", "1859")
        put("uin", "0")
    }

    private fun isHexPayload(value: String): Boolean {
        if (value.length < 32 || value.length % 2 != 0) return false
        return value.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
    }

    private fun decodeQqLyricPayload(raw: String): String {
        if (raw.isBlank()) return ""
        if (isHexPayload(raw)) {
            return QrcDecrypter.decrypt(raw)?.takeIf { it.isNotBlank() } ?: raw
        }
        val decoded = decodeBase64OrSelf(raw)
        if (isHexPayload(decoded)) {
            return QrcDecrypter.decrypt(decoded)?.takeIf { it.isNotBlank() } ?: decoded
        }
        return decoded
    }

    override fun searchCandidates(query: String): List<SearchCandidate> {
        return runCatching {
            val items = searchSongs(0L, query.trim(), "manual") ?: return emptyList()
            val list = mutableListOf<SearchCandidate>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val mid = QqSearchResponseParser.mid(item)
                if (mid.isBlank()) continue
                list.add(
                    SearchCandidate(
                        id = mid,
                        title = QqSearchResponseParser.title(item),
                        artist = QqSearchResponseParser.artist(item),
                        album = QqSearchResponseParser.album(item),
                        source = "QQ音乐",
                        coverUrl = QqSearchResponseParser.coverUrl(item),
                        durationMs = QqSearchResponseParser.durationMs(item)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? {
        return fetch(candidate.id, 0L, TrackQuery(candidate.title, candidate.artist, candidate.album))
    }
}
