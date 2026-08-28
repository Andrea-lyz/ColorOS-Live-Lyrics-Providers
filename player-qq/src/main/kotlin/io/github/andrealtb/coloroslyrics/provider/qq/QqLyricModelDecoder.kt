/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import kotlin.math.abs

/**
 * Reads QQ `com.lyricengine.base.k` / `t` / `a` documents by the documented
 * field names. Translation comes only from `LyricLoadBean.h()`; romaji from
 * `e()` is ignored.
 */
object QqLyricModelDecoder {
    /**
     * Same bound as the old Bridge QQ adapter (`INTERNAL_TRANSLATION_MATCH_MS`).
     * Neighbor spacing still tightens this so a late line cannot steal the next
     * line's translation. Do not switch to unbounded `findClosest`.
     */
    private const val MAX_ALIGN_MS = 1_500L

    fun decodePrimary(lyricObject: Any?): List<RichLyricLine> =
        decodeDocument(lyricObject, includeWords = true)

    fun decodeTranslation(lyricObject: Any?): List<RichLyricLine> =
        decodeDocument(lyricObject, includeWords = false)

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

    /**
     * QQ translation `k` timestamps often sit on the singing start (first word)
     * while QRC `t.b` is an earlier line slot. Love Story
     * (`lyrics-log-20260827-073906.txt`) had 600ms and 2000ms pre-roll, so a
     * 500ms compare of `line.begin` only dropped those translations.
     */
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

    private fun decodeDocument(lyricObject: Any?, includeWords: Boolean): List<RichLyricLine> {
        if (lyricObject == null) return emptyList()
        val sourceLines = asIterable(readField(lyricObject, "e")) ?: return emptyList()
        return sourceLines.mapNotNull { sourceLine ->
            parseLine(sourceLine, includeWords)
        }.sortedBy { it.begin }
    }

    private fun parseLine(sourceLine: Any?, includeWords: Boolean): RichLyricLine? {
        if (sourceLine == null) return null
        val lineText = clean(readStringField(sourceLine, "a"))
        val lineStart = readLongField(sourceLine, "b", -1L)
        val lineDuration = readLongField(sourceLine, "c", 0L)
        if (lineStart < 0L) return null
        val rawWords = readRawWords(sourceLine)
        val text = firstNonBlank(lineText, joinedWordText(rawWords)).orEmpty()
        if (text.isBlank()) return null
        val lineEnd = if (lineDuration > 0L) lineStart + lineDuration else {
            rawWords.maxOfOrNull { it.startMillis + it.durationMillis } ?: (lineStart + 3_000L)
        }
        val axis = QqQrcWordTimePolicy.resolveForQQMusicQrc(
            lineBegin = lineStart,
            lineEnd = lineEnd,
            lineDuration = lineDuration,
            rawWordBegins = rawWords.map { it.startMillis }
        )
        val words = if (!includeWords) {
            emptyList()
        } else {
            rawWords.mapNotNull { word ->
                val begin = axis.toAbsolute(word.startMillis)
                val duration = word.durationMillis.takeIf { it > 0L } ?: 0L
                if (word.text.isBlank()) return@mapNotNull null
                LyricWord(
                    begin = begin,
                    end = begin + duration,
                    duration = duration,
                    text = word.text
                )
            }
        }
        return RichLyricLine(
            begin = lineStart,
            end = lineEnd,
            duration = (lineEnd - lineStart).coerceAtLeast(0L),
            text = text,
            words = words.takeIf { it.isNotEmpty() }
        )
    }

    private fun readRawWords(sourceLine: Any): List<RawWord> {
        val sourceWords = asIterable(readField(sourceLine, "g")) ?: return emptyList()
        return sourceWords.mapNotNull { sourceWord ->
            val start = readLongField(sourceWord, "a", -1L)
            val duration = readLongField(sourceWord, "b", 0L)
            val text = clean(
                firstNonBlank(
                    readStringField(sourceWord, "e"),
                    readStringField(sourceWord, "text"),
                    readStringField(sourceWord, "word")
                ).orEmpty()
            )
            if (start < 0L || text.isBlank()) null else RawWord(start, duration, text)
        }
    }

    private fun joinedWordText(words: List<RawWord>): String =
        words.joinToString(separator = "") { it.text }

    private fun isUsableTranslation(translation: String?, primary: String?): Boolean {
        val clean = clean(translation.orEmpty())
        return clean.isNotBlank() && clean != "//" && clean != clean(primary.orEmpty())
    }

    internal fun readField(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val field = runCatching { type.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(target) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    internal fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val method = type.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                return runCatching { method.invoke(target) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun readStringField(target: Any?, fieldName: String): String =
        readField(target, fieldName)?.toString().orEmpty()

    private fun readLongField(target: Any?, fieldName: String, fallback: Long): Long {
        val value = readField(target, fieldName) ?: return fallback
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun asIterable(value: Any?): Iterable<*>? = when (value) {
        is Iterable<*> -> value
        is Array<*> -> value.asList()
        else -> null
    }

    private fun clean(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private data class RawWord(
        val startMillis: Long,
        val durationMillis: Long,
        val text: String
    )
}
