/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.LrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.util.Locale

object QishuiLyricDecoder {
    fun decode(cache: QishuiNetResponseCache, locale: Locale = Locale.getDefault()): List<RichLyricLine> {
        val lyric = cache.lyric ?: return emptyList()
        val sourceLines = parse(lyric.type, lyric.resolvedContent)
        if (sourceLines.isEmpty()) return emptyList()

        val translationKey = selectTranslationKey(
            lyric.lang_translations.orEmpty().keys,
            locale
        )
        val translation = lyric.lang_translations?.get(translationKey)
        val translatedLines = parse(translation?.type, translation?.resolvedContent)
        val matches = matchTranslationLines(sourceLines, translatedLines)

        return sourceLines.mapIndexed { index, line ->
            val translated = matches[index]?.text
                ?.takeIf { it.isNotBlank() && it != line.text && it != "//" }
            line.copy(secondary = translated)
        }
    }

    internal fun parse(type: String?, content: String?): List<RichLyricLine> {
        if (type.isNullOrBlank() || content.isNullOrBlank()) return emptyList()
        val parsed = when (type.lowercase(Locale.ROOT)) {
            "krc" -> QishuiKtvLyricParser.parse(content)
            "lrc" -> LrcParser.parse(content).lines.map { line ->
                RichLyricLine(
                    begin = line.begin,
                    end = line.end,
                    duration = line.duration,
                    text = line.text
                )
            }
            else -> emptyList()
        }
        return normalize(parsed)
    }

    internal fun matchTranslationLines(
        sourceLines: List<RichLyricLine>,
        translationLines: List<RichLyricLine>
    ): List<RichLyricLine?> {
        if (sourceLines.isEmpty() || translationLines.isEmpty()) {
            return List(sourceLines.size) { null }
        }
        val used = BooleanArray(translationLines.size)
        return sourceLines.mapIndexed { index, source ->
            val tolerance = localTranslationTolerance(sourceLines, index)
            var bestIndex = -1
            var bestDistance = Long.MAX_VALUE
            translationLines.forEachIndexed { translationIndex, candidate ->
                if (used[translationIndex]) return@forEachIndexed
                val distance = absoluteDifference(source.begin, candidate.begin)
                if (distance <= tolerance && distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = translationIndex
                }
            }
            if (bestIndex < 0) {
                null
            } else {
                used[bestIndex] = true
                translationLines[bestIndex]
            }
        }
    }

    internal fun selectTranslationKey(keys: Set<String>, locale: Locale): String? {
        val allowed = keys.filterNot(::isPronunciationKey)
        if (allowed.isEmpty()) return null
        val systemTag = buildString {
            append(locale.language.uppercase(Locale.ROOT))
            if (locale.script.isNotEmpty()) append("-" + locale.script.uppercase(Locale.ROOT))
            if (locale.country.isNotEmpty()) append("-" + locale.country.uppercase(Locale.ROOT))
        }
        allowed.firstOrNull { it.equals(systemTag, ignoreCase = true) }?.let { return it }
        if (locale.language.equals("zh", ignoreCase = true)) {
            val country = locale.country.uppercase(Locale.ROOT)
            allowed.firstOrNull {
                it.equals("ZH-HANS-" + country, ignoreCase = true)
            }?.let { return it }
            allowed.firstOrNull {
                it.equals("ZH-HANT-" + country, ignoreCase = true)
            }?.let { return it }
        }
        return allowed.firstOrNull { it.startsWith(locale.language, ignoreCase = true) }
            ?: allowed.firstOrNull(::isPreferredChineseKey)
            ?: allowed.firstOrNull { it.startsWith("ZH", ignoreCase = true) }
            ?: allowed.firstOrNull()
    }

    private fun normalize(lines: List<RichLyricLine>): List<RichLyricLine> {
        val sorted = lines
            .filter { it.begin >= 0L && !it.text.isNullOrBlank() }
            .sortedBy { it.begin }
        return sorted.mapIndexed { index, line ->
            val fallbackEnd = sorted.getOrNull(index + 1)?.begin?.takeIf { it > line.begin }
                ?: line.begin + 5_000L
            val end = line.end.takeIf { it > line.begin } ?: fallbackEnd
            val words = line.words.orEmpty().map { word ->
                val begin = word.begin.coerceAtLeast(line.begin)
                val wordEnd = word.end.coerceAtLeast(begin)
                word.copy(begin = begin, end = wordEnd, duration = wordEnd - begin)
            }
            line.copy(
                end = end,
                duration = end - line.begin,
                words = words.takeIf { it.isNotEmpty() }
            )
        }
    }

    private fun localTranslationTolerance(lines: List<RichLyricLine>, index: Int): Long {
        val current = lines[index].begin
        val gaps = buildList {
            lines.getOrNull(index - 1)?.begin?.let { previous ->
                if (current > previous) add(current - previous)
            }
            lines.getOrNull(index + 1)?.begin?.let { next ->
                if (next > current) add(next - current)
            }
        }
        val localSpacing = gaps.minOrNull() ?: 1_500L
        return (localSpacing / 3L).coerceIn(80L, 800L)
    }

    private fun absoluteDifference(first: Long, second: Long): Long {
        if (first == second) return 0L
        val difference = if (first > second) first - second else second - first
        return if (difference < 0L) Long.MAX_VALUE else difference
    }

    private fun isPronunciationKey(key: String): Boolean {
        val value = key.lowercase(Locale.ROOT)
        return value.contains("romaji") ||
            value.contains("roman") ||
            value.contains("translit") ||
            value.contains("pronunciation") ||
            value.contains("pinyin")
    }

    private fun isPreferredChineseKey(key: String): Boolean = when (key.uppercase(Locale.ROOT)) {
        "ZH-HANS", "ZH-HANS-CN", "ZH-CN", "ZH" -> true
        else -> false
    }
}
