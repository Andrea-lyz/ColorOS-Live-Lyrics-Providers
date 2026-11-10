package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics

class LyricsSourceAggregator(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    val qqSource: LyricsSource = QQMusicSource(client),
    val netEaseSource: LyricsSource = NetEaseSource(client),
    val appleMusicSource: LyricsSource = AppleMusicLyricSource(client)
) {
    companion object {
        // Accessed by app IO jobs, so entering a screen never initializes the network stack.
        val shared: LyricsSourceAggregator by lazy { LyricsSourceAggregator() }
    }

    enum class SourceId(val label: String) {
        QQ("QQ音乐"),
        NETEASE("网易云音乐"),
        APPLE_MUSIC("Apple Music")
    }

    fun source(id: SourceId): LyricsSource = when (id) {
        SourceId.QQ -> qqSource
        SourceId.NETEASE -> netEaseSource
        SourceId.APPLE_MUSIC -> appleMusicSource
    }

    fun sourceOf(label: String): LyricsSource? =
        SourceId.values().firstOrNull { it.label == label }?.let(::source)

    /** Manual search for one source; a failing source yields no rows instead of throwing. */
    fun searchCandidates(id: SourceId, query: String): List<SearchCandidate> =
        runCatching { source(id).searchCandidates(query) }.getOrDefault(emptyList()).distinctBy { it.source + it.id }

    /** Manual binding for one user-picked row; the row's own label selects the source. */
    fun fetchByCandidate(candidate: SearchCandidate): LyricResult? =
        sourceOf(candidate.source)?.let { source ->
            runCatching { source.fetchLyricByCandidate(candidate) }.getOrNull()
        }

    /**
     * Fetches the best lyric for [query] under the user's source order.
     *
     * Priority is the contract: a lower-ranked source may only win when the higher-ranked one had
     * nothing usable. Without [preferWordTiming] the first source that returns real lyrics wins
     * outright; with it the first word-timed answer wins, and a line-timed answer from a higher
     * priority source still beats a line-timed answer from a lower one.
     *
     * A pure-music/credits-only payload never short-circuits the walk, so an "instrumental" entry
     * in one catalogue cannot block a real lyric in the next one.
     */
    fun fetchFirst(
        query: TrackQuery,
        priority: List<SourceId> = SourceId.values().toList(),
        preferWordTiming: Boolean = false
    ): FetchReport {
        val ordered = priority.distinct()
        if (ordered.isEmpty()) {
            return FetchReport(result = null, transientFailure = false, attemptCount = 0, reason = "priorityEmpty")
        }
        val priorityText = ordered.joinToString(",") { it.name }
        var transientFailure = false
        val reasons = mutableListOf<String>()
        var lineFallback: LyricResult? = null
        var instrumentalFallback: LyricResult? = null

        for (id in ordered) {
            val started = System.currentTimeMillis()
            UniversalDiagnostics.sourceAttempt(id.name, query.generation, priorityText)
            val attempt = runCatching {
                when (id) {
                    SourceId.QQ -> qqSource.fetchLyrics(query)
                    SourceId.NETEASE -> netEaseSource.fetchLyrics(query)
                    SourceId.APPLE_MUSIC -> appleMusicSource.fetchLyrics(query)
                }
            }
            val elapsed = System.currentTimeMillis() - started
            val result = attempt.getOrNull()
            if (attempt.isFailure) {
                transientFailure = true
                val exceptionName = attempt.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"
                UniversalDiagnostics.sourceFailed(id.name, query.generation, exceptionName, elapsed)
                reasons += id.name + ":exception:" + exceptionName
                continue
            }
            if (result == null || result.lyric.isBlank()) {
                UniversalDiagnostics.sourceEmpty(id.name, query.generation, elapsed, "blankOrUnmatched")
                reasons += id.name + ":empty"
                continue
            }

            val instrumental = SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)
            if (instrumental) {
                reasons += id.name + ":instrumental"
                if (instrumentalFallback == null) instrumentalFallback = result
                continue
            }

            val score = qualityScore(result)
            reasons += id.name + ":hit:" + score
            val wordTimed = WordTimedLyricConverter.hasRealWordTiming(result.rawLyric ?: result.lyric)
            if (!preferWordTiming || wordTimed) {
                return FetchReport(
                    result = result,
                    transientFailure = false,
                    attemptCount = reasons.size,
                    reason = "hit:" + id.name + if (wordTimed) ":wordTimed" else ":line"
                )
            }
            if (lineFallback == null) lineFallback = result
        }

        lineFallback?.let {
            return FetchReport(
                result = it,
                transientFailure = false,
                attemptCount = reasons.size,
                reason = "fallback:" + it.source
            )
        }
        instrumentalFallback?.let {
            return FetchReport(
                result = it,
                transientFailure = false,
                attemptCount = reasons.size,
                reason = "instrumental:" + it.source
            )
        }
        return FetchReport(
            result = null,
            transientFailure = transientFailure,
            attemptCount = reasons.size,
            reason = if (reasons.isEmpty()) "noSources" else reasons.joinToString(";")
        )
    }

    private fun qualityScore(result: LyricResult): Int {
        var score = 1
        if (WordTimedLyricConverter.hasRealWordTiming(result.rawLyric ?: result.lyric)) score += 100
        if (!result.translationLyric.isNullOrBlank()) score += 10
        return score
    }
}
