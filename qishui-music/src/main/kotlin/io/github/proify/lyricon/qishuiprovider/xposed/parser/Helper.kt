package io.github.proify.lyricon.qishuiprovider.xposed.parser

import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.extensions.normalize
import java.util.Locale

fun NetResponseCache.toRichLyric(): List<RichLyricLine> {
    val lines = parserTypeLyric(lyric?.type, lyric?.resolvedContent)?.normalize()
    if (lines.isNullOrEmpty()) return emptyList()

    val langKey = lyric?.lang_translations?.keys?.let(::getLangKeyForTranslations)
    val translation = lyric?.lang_translations?.get(langKey.orEmpty())
    val translationLines = parserTypeLyric(
        translation?.type,
        translation?.resolvedContent
    )?.normalize().orEmpty()
    val matchedTranslations = matchTranslationLines(lines, translationLines)

    return lines.mapIndexed { index, line ->
        val translatedText = matchedTranslations[index]?.text
        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = line.text,
            words = line.words,
            translation = if (translatedText == line.text) null else translatedText
        )
    }
}

private fun parserTypeLyric(type: String?, lyric: String?): List<LyricLine>? {
    if (type.isNullOrBlank() || lyric.isNullOrBlank()) return null
    return when (type.lowercase(Locale.ROOT)) {
        "krc" -> KtvLyricParser.parse(lyric)
        "lrc" -> LrcParser.parse(lyric).lines
        else -> null
    }
}

/**
 * Matches each translated line at most once. The tolerance follows the local source-line spacing,
 * accepting small provider offsets without allowing a translation to drift into a neighbour.
 */
internal fun matchTranslationLines(
    sourceLines: List<LyricLine>,
    translationLines: List<LyricLine>
): List<LyricLine?> {
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

private fun localTranslationTolerance(lines: List<LyricLine>, index: Int): Long {
    val current = lines[index].begin
    val gaps = buildList {
        lines.getOrNull(index - 1)?.begin?.let { previous ->
            if (current > previous) add(current - previous)
        }
        lines.getOrNull(index + 1)?.begin?.let { next ->
            if (next > current) add(next - current)
        }
    }
    val localSpacing = gaps.minOrNull() ?: DEFAULT_TRANSLATION_LINE_SPACING_MS
    return (localSpacing / 3L).coerceIn(
        MIN_TRANSLATION_TOLERANCE_MS,
        MAX_TRANSLATION_TOLERANCE_MS
    )
}

private fun absoluteDifference(first: Long, second: Long): Long {
    if (first == second) return 0L
    val difference = if (first > second) first - second else second - first
    return if (difference < 0L) Long.MAX_VALUE else difference
}

/** Selects the closest translation locale exposed by QiShui. */
private fun getLangKeyForTranslations(availableKeys: Set<String>): String? {
    val locale = Locale.getDefault()
    val systemTag = buildString {
        append(locale.language.uppercase(Locale.ROOT))
        if (locale.script.isNotEmpty()) append("-${locale.script.uppercase(Locale.ROOT)}")
        if (locale.country.isNotEmpty()) append("-${locale.country.uppercase(Locale.ROOT)}")
    }

    availableKeys.firstOrNull { it.equals(systemTag, ignoreCase = true) }?.let { return it }

    if (locale.language == "zh") {
        val country = locale.country.uppercase(Locale.ROOT)
        val fallbackHans = "ZH-HANS-$country"
        availableKeys.firstOrNull { it.equals(fallbackHans, ignoreCase = true) }?.let { return it }

        val fallbackHant = "ZH-HANT-$country"
        availableKeys.firstOrNull { it.equals(fallbackHant, ignoreCase = true) }?.let { return it }
    }

    return availableKeys.firstOrNull { it.startsWith(locale.language, ignoreCase = true) }
        ?: availableKeys.firstOrNull(::isPreferredChineseTranslationKey)
        ?: availableKeys.firstOrNull { it.startsWith("ZH", ignoreCase = true) }
        ?: availableKeys.firstOrNull()
}

private fun isPreferredChineseTranslationKey(key: String): Boolean {
    return when (key.uppercase(Locale.ROOT)) {
        "ZH-HANS", "ZH-HANS-CN", "ZH-CN", "ZH" -> true
        else -> false
    }
}

private const val DEFAULT_TRANSLATION_LINE_SPACING_MS = 1_500L
private const val MIN_TRANSLATION_TOLERANCE_MS = 80L
private const val MAX_TRANSLATION_TOLERANCE_MS = 800L
