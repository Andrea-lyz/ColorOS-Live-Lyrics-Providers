package io.github.andrealtb.coloroslyrics.provider.universal.api

data class TrackQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val generation: Long = 0L
)

data class LyricResult(
    val title: String,
    val artist: String,
    val lyric: String,
    val rawLyric: String? = null,
    val translationLyric: String? = null,
    val source: String,
    val sourceTrackId: String? = null,
    /** Normalized TTML for the optional `lyricInfo.ttml` lane (duet sides, background vocals). */
    val ttml: String? = null,
    /** The format the source actually delivered, one of [LyricFormats]. */
    val format: String = "",
    /** True when [ttml] was generated from [format] instead of delivered by the source. */
    val ttmlConverted: Boolean = false
)

/** Labels of the lyric formats the sources deliver. */
object LyricFormats {
    const val TTML = "TTML"
    const val QRC = "QRC"
    const val YRC = "YRC"
    const val KRC = "KRC"
    /** Word-timed LRC (`<mm:ss.xx>` stamps), as the paxsenix Apple JSON converts to. */
    const val ENHANCED_LRC = "ELRC"
    const val LRC = "LRC"

    /** The label shown for a payload: `QRC`, or `QRC→TTML` when it was converted. */
    fun label(format: String, converted: Boolean): String =
        if (format.isBlank()) "" else if (converted) "$format→$TTML" else format
}

data class SearchCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val coverUrl: String = "",
    val durationMs: Long = 0L,
    /**
     * The format this row is known to have before it is fetched, when the search itself says so
     * (official Apple Music reports synced lyrics). Blank means unknown until fetched.
     */
    val formatHint: String = ""
)

data class FetchReport(
    val result: LyricResult?,
    val transientFailure: Boolean,
    val attemptCount: Int,
    val reason: String
)

interface LyricsSource {
    val name: String
    /** True when the source can deliver TTML itself (used by the TTML-first switch). */
    val deliversTtml: Boolean get() = false
    fun fetchLyrics(query: TrackQuery): LyricResult?
    fun searchCandidates(query: String): List<SearchCandidate> = emptyList()
    fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? = null
}
