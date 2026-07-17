package io.github.proify.lyricon.qishuiprovider.xposed.parser

import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import kotlin.math.max

/** Parses QiShui KRC into one canonical representation: absolute millisecond word times. */
object KtvLyricParser {

    private val lineTimeRegex = Regex("""^\s*\[(\d+),(\d+)]""")
    private val wordTimeRegex = Regex("""<(\d+),(\d+),(?:\d+)>""")

    fun parse(content: String?, inferUntaggedTime: Boolean = true): List<LyricLine> {
        if (content.isNullOrBlank()) return emptyList()

        val parsed = content.lineSequence()
            .mapNotNull(::parseLine)
            .toList()

        return parsed.mapIndexed { index, value ->
            val nextBegin = parsed.getOrNull(index + 1)?.begin
            value.toLyricLine(nextBegin, inferUntaggedTime)
        }
    }

    private fun parseLine(rawLine: String): ParsedLine? {
        if (rawLine.isBlank()) return null
        val lineMatch = lineTimeRegex.find(rawLine) ?: return null
        val lineBegin = lineMatch.groupValues[1].toLongOrNull() ?: return null
        val declaredDuration = lineMatch.groupValues[2].toLongOrNull() ?: return null
        val body = rawLine.substring(lineMatch.range.last + 1)
        val matches = wordTimeRegex.findAll(body).toList()

        val segments = when {
            matches.isEmpty() -> emptyList()
            body.substring(0, matches.first().range.first).isBlank() -> {
                parsePrefixTimedSegments(body, matches, lineBegin)
            }
            else -> parseSuffixTimedSegments(body, matches, lineBegin)
        }

        return ParsedLine(
            begin = lineBegin,
            declaredDuration = declaredDuration,
            text = wordTimeRegex.replace(body, ""),
            segments = clampMonotonic(segments)
        )
    }

    /** Standard KRC form: `<offset,duration,flag>text`. */
    private fun parsePrefixTimedSegments(
        body: String,
        matches: List<MatchResult>,
        lineBegin: Long
    ): List<TimedSegment> = matches.mapIndexedNotNull { index, match ->
        val textStart = match.range.last + 1
        val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
        val text = body.substring(textStart, textEnd)
        timedSegment(match, text, lineBegin)
    }

    /** Compatibility form seen in a few converted files: `text<offset,duration,flag>`. */
    private fun parseSuffixTimedSegments(
        body: String,
        matches: List<MatchResult>,
        lineBegin: Long
    ): List<TimedSegment> {
        val result = mutableListOf<TimedSegment>()
        var textStart = 0
        matches.forEach { match ->
            val text = body.substring(textStart, match.range.first)
            timedSegment(match, text, lineBegin)?.let(result::add)
            textStart = match.range.last + 1
        }

        val trailing = body.substring(textStart)
        if (trailing.isNotEmpty()) {
            val previous = result.lastOrNull()
            if (previous != null) {
                result[result.lastIndex] = previous.copy(text = previous.text + trailing)
            }
        }
        return result
    }

    private fun timedSegment(
        match: MatchResult,
        text: String,
        lineBegin: Long
    ): TimedSegment? {
        if (text.isEmpty()) return null
        val offset = match.groupValues[1].toLongOrNull() ?: return null
        val duration = match.groupValues[2].toLongOrNull() ?: return null
        val begin = safeAdd(lineBegin, offset)
        return TimedSegment(
            text = text,
            begin = begin,
            end = safeAdd(begin, duration),
            duration = duration
        )
    }

    private fun clampMonotonic(segments: List<TimedSegment>): List<TimedSegment> {
        var previousBegin = Long.MIN_VALUE
        return segments.map { segment ->
            val begin = max(segment.begin, previousBegin)
            val end = max(begin, segment.end)
            previousBegin = begin
            segment.copy(begin = begin, end = end, duration = end - begin)
        }
    }

    private data class ParsedLine(
        val begin: Long,
        val declaredDuration: Long,
        val text: String,
        val segments: List<TimedSegment>
    ) {
        fun toLyricLine(nextBegin: Long?, inferUntaggedTime: Boolean): LyricLine {
            val declaredEnd = safeAdd(begin, declaredDuration)
            val wordEnd = segments.maxOfOrNull { it.end } ?: begin
            val inferredEnd = nextBegin?.takeIf { it > begin } ?: begin
            val end = max(
                max(begin, wordEnd),
                if (declaredDuration > 0L || !inferUntaggedTime) declaredEnd else inferredEnd
            )
            return LyricLine(
                begin = begin,
                end = end,
                duration = end - begin
            ).also { line ->
                line.text = text
                line.words = segments.map { segment ->
                    LyricWord(
                        begin = segment.begin,
                        end = segment.end,
                        duration = segment.duration,
                        text = segment.text
                    )
                }
            }
        }
    }

    private data class TimedSegment(
        val text: String,
        val begin: Long,
        val end: Long,
        val duration: Long
    )

    private fun safeAdd(first: Long, second: Long): Long {
        if (second <= 0L) return first
        return if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
    }
}
