package io.github.andrealtb.coloroslyrics.provider.universal.api

import java.util.Locale

/**
 * Converts a source's timed lyric into TTML for the TTML-first switch.
 *
 * The document has the shape the Lyrico TTML writer produces (Replica0110/Lyrico, Apache-2.0):
 * one `<p>` per line with a running `itunes:key`, one `<span>` per timed word, and
 * `itunes:timing` saying whether it is word- or line-timed. QRC, YRC, KRC and LRC name no
 * singers, so every line belongs to one agent (`v1`): the converted lane never invents a duet.
 * Translations stay in the translation lane, as for delivered TTML.
 */
internal object TtmlLyricWriter {
    private val LINE_STAMP = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** The result with a converted `ttml` lane, or unchanged when it already has one or no timing. */
    fun withConvertedTtml(result: LyricResult): LyricResult {
        if (!result.ttml.isNullOrBlank()) return result
        val lines = parse(result.rawLyric?.takeIf { it.isNotBlank() } ?: result.lyric)
        if (lines.isEmpty()) return result
        val lane = TtmlLyrics.lane(write(lines)) ?: return result
        return result.copy(ttml = lane, ttmlConverted = true)
    }

    /** Lines of an enhanced (`<mm:ss.xx>` words) or plain LRC. */
    fun parse(lrc: String): List<TimedLine> {
        val rows = lrc.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val stamp = LINE_STAMP.find(line) ?: return@mapNotNull null
            val start = millis(stamp)
            val body = line.substring(stamp.range.last + 1)
            val stamps = WORD_STAMP.findAll(body).toList()
            val words = if (stamps.isEmpty()) {
                emptyList()
            } else {
                stamps.mapIndexedNotNull { index, word ->
                    val textEnd = stamps.getOrNull(index + 1)?.range?.first ?: body.length
                    val text = body.substring(word.range.last + 1, textEnd)
                    if (text.isEmpty()) return@mapIndexedNotNull null
                    val wordStart = millis(word)
                    val wordEnd = stamps.getOrNull(index + 1)?.let(::millis) ?: wordStart
                    TimedWord(wordStart, wordEnd.coerceAtLeast(wordStart), text)
                }
            }
            val text = if (stamps.isEmpty()) body.trim() else body.replace(WORD_STAMP, "").trim()
            if (text.isEmpty()) null else Triple(start, text, words)
        }.sortedBy { it.first }.toList()
        return rows.mapIndexed { index, (start, text, words) ->
            val next = rows.getOrNull(index + 1)?.first
            val end = words.lastOrNull()?.endMs?.takeIf { it > start }
                ?: next?.coerceAtLeast(start)
                ?: (start + 5_000L)
            TimedLine(start, end, words.ifEmpty { listOf(TimedWord(start, end, text)) })
        }
    }

    fun write(lines: List<TimedLine>): String {
        val wordTimed = lines.any { it.words.size > 1 }
        val end = lines.maxOf { it.endMs }
        return buildString {
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\"")
            append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"")
            append(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"")
            append(" itunes:timing=\"").append(if (wordTimed) "Word" else "Line").append("\">")
            append("<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/></metadata></head>")
            append("<body dur=\"").append(time(end)).append("\"><div>")
            lines.forEachIndexed { index, line ->
                append("<p begin=\"").append(time(line.startMs))
                append("\" end=\"").append(time(line.endMs))
                append("\" itunes:key=\"L").append(index + 1)
                append("\" ttm:agent=\"v1\">")
                if (line.words.size > 1) {
                    line.words.forEach { word ->
                        // A trailing space belongs between spans, where TTML keeps it as word spacing.
                        val trimmed = word.text.trimEnd()
                        append("<span begin=\"").append(time(word.startMs))
                        append("\" end=\"").append(time(word.endMs)).append("\">")
                        append(escape(trimmed)).append("</span>")
                        if (trimmed.length < word.text.length) append(' ')
                    }
                } else {
                    append(escape(line.text.trim()))
                }
                append("</p>")
            }
            append("</div></body></tt>")
        }
    }

    /** `mm:ss.mmm`, or `h:mm:ss.mmm` past an hour, as the TTML clock-time form allows. */
    internal fun time(ms: Long): String {
        val value = ms.coerceAtLeast(0L)
        val hours = value / 3_600_000L
        val minutes = (value % 3_600_000L) / 60_000L
        val seconds = (value % 60_000L) / 1_000L
        val millis = value % 1_000L
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
        } else {
            String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
        }
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun millis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val ms = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + ms
    }
}
