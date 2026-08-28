/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.LrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricLine
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import io.github.andrealtb.coloroslyrics.provider.parser.yrc.YrcParser
import kotlin.math.abs

object NeteaseLyricDecoder {
    private const val MAX_ALIGN_MS = 1_500L
    private val YRC_HEADER = Regex("""\[\s*-?\d+\s*,\s*\d+\s*]""")

    fun decode(snapshot: NeteaseLyricInfoReader.Snapshot): List<RichLyricLine> {
        val primary = parsePrimary(snapshot.yrc, snapshot.lrc)
        val translation = parseTranslation(snapshot.yrcTranslate, snapshot.lrcTranslate)
        return mergeTranslation(primary, translation)
    }

    fun parsePrimary(yrc: String?, lrc: String?): List<RichLyricLine> {
        yrc?.takeIf { it.isNotBlank() }?.let { raw ->
            val parsed = YrcParser.parse(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        return lrcLines(lrc)
    }

    fun parseTranslation(yrcTranslate: String?, lrcTranslate: String?): List<RichLyricLine> {
        yrcTranslate?.takeIf { it.isNotBlank() }?.let { raw ->
            val parsed = if (looksLikeYrc(raw)) {
                YrcParser.parse(raw).map { it.copy(words = null) }
            } else {
                lrcLines(raw)
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return lrcLines(lrcTranslate)
    }

    fun mergeTranslation(
        primary: List<RichLyricLine>,
        translation: List<RichLyricLine>
    ): List<RichLyricLine> {
        if (primary.isEmpty() || translation.isEmpty()) return primary
        var transIndex = 0
        return primary.mapIndexed { index, line ->
            val window = alignmentWindowMs(primary, index)
            val earliestPrimary = alignTimes(line).min()
            while (transIndex < translation.size &&
                alignTimes(translation[transIndex]).all { it < earliestPrimary - window }
            ) {
                transIndex += 1
            }
            val candidate = translation.getOrNull(transIndex) ?: return@mapIndexed line
            if (!isTimeMatch(line, candidate, window)) {
                return@mapIndexed line
            }
            transIndex += 1
            if (isUsableTranslation(candidate.text, line.text)) {
                line.copy(secondary = candidate.text)
            } else {
                line
            }
        }
    }

    fun looksLikeYrc(raw: String): Boolean = YRC_HEADER.containsMatchIn(raw)

    private fun lrcLines(raw: String?): List<RichLyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        return LrcParser.parse(raw).lines.map { line -> toRich(line) }
    }

    private fun toRich(line: LyricLine): RichLyricLine = RichLyricLine(
        begin = line.begin,
        end = line.end,
        duration = line.duration,
        text = line.text
    )

    private fun alignTimes(line: RichLyricLine): LongArray {
        val firstWord = line.words?.firstOrNull()?.begin
        return if (firstWord != null && firstWord != line.begin) {
            longArrayOf(line.begin, firstWord)
        } else {
            longArrayOf(line.begin)
        }
    }

    private fun singingTime(line: RichLyricLine): Long =
        line.words?.firstOrNull()?.begin ?: line.begin

    private fun isTimeMatch(
        primary: RichLyricLine,
        translation: RichLyricLine,
        window: Long
    ): Boolean {
        val primaryTimes = alignTimes(primary)
        val translationTimes = alignTimes(translation)
        return primaryTimes.any { primaryTime ->
            translationTimes.any { translationTime ->
                abs(primaryTime - translationTime) <= window
            }
        }
    }

    private fun alignmentWindowMs(primary: List<RichLyricLine>, index: Int): Long {
        val singing = singingTime(primary[index])
        val neighborDistances = listOfNotNull(
            primary.getOrNull(index - 1)?.let { singingTime(it) },
            primary.getOrNull(index + 1)?.let { singingTime(it) }
        ).map { abs(it - singing) }
        return neighborDistances.minOrNull()
            ?.div(2)
            ?.coerceAtMost(MAX_ALIGN_MS)
            ?: MAX_ALIGN_MS
    }

    private fun isUsableTranslation(translation: String?, primary: String?): Boolean {
        val clean = translation?.replace('\r', ' ')?.replace('\n', ' ')?.trim().orEmpty()
        return clean.isNotBlank() &&
            clean != "//" &&
            clean != primary?.replace('\r', ' ')?.replace('\n', ' ')?.trim().orEmpty()
    }
}
