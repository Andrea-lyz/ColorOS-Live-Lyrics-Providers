/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

/**
 * Patches KuGou's own lyricInfo JSON. Official `id` / `songId` / `lyricType` /
 * `lyric` / `noLyric` stay in place; `rawLyric` and `translationLyric` are added.
 * Romaji must never enter `translationLyric`. This is not a Bridge envelope.
 */
object KuGouOfficialLyricInfoEncoder {
    const val SOURCE = KuGouPlayerConstants.SOURCE_INTERNAL
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
    private val JSON_STRING_FIELD =
        Regex(""""([^"\\]+)":\s*"((?:\\.|[^"\\])*)"""")

    data class Encoded(
        val value: String,
        val plainLyric: String,
        val rawLyric: String,
        val translationLyric: String
    )

    fun encode(
        track: TrackIdentity,
        lines: List<RichLyricLine>,
        trackGeneration: Long,
        hostPackage: String,
        existingLyricInfo: String? = null
    ): Encoded? {
        val sanitized = sanitize(lines, track)
        if (sanitized.isEmpty()) return null

        val plainLyric = toPlainLrc(track, sanitized)
        if (plainLyric.isBlank()) return null
        val rawLyric = toEnhancedLrc(track, sanitized)
        val translationLyric = toTranslationLrc(sanitized)
        val existing = existingLyricInfo?.trim().orEmpty()
        val officialLyric = extractJsonString(existing, "lyric")
            ?.takeIf { it.isNotBlank() }
            ?.let { stripPromoLinesFromLrc(it) }
            ?: plainLyric
        val songId = firstNonBlank(
            extractJsonString(existing, "songId"),
            track.id
        ).orEmpty()
        val officialId = extractJsonRaw(existing, "id") ?: "0"
        val lyricType = extractJsonRaw(existing, "lyricType") ?: "0"

        val fields = linkedMapOf(
            "id" to officialId,
            "songId" to jsonQuote(songId),
            "songName" to jsonQuote(track.title.orEmpty()),
            "artist" to jsonQuote(track.artist.orEmpty()),
            "lyricType" to lyricType,
            "lyric" to jsonQuote(officialLyric),
            "noLyric" to "false",
            "provider" to jsonQuote(hostPackage),
            "source" to jsonQuote(SOURCE),
            "trackKey" to jsonQuote(KuGouTrackIdentity.trackKey(track.title, track.artist)),
            "sessionGeneration" to trackGeneration.toString()
        )
        if (containsTimedLrc(rawLyric)) {
            fields["rawLyric"] = jsonQuote(rawLyric)
        }
        if (containsTimedLrc(translationLyric)) {
            fields["translationLyric"] = jsonQuote(translationLyric)
        }
        return Encoded(
            value = fields.entries.joinToString(
                prefix = "{",
                postfix = "}"
            ) { "\"${it.key}\":${it.value}" },
            plainLyric = officialLyric,
            rawLyric = rawLyric,
            translationLyric = translationLyric
        )
    }

    fun sanitize(
        lines: List<RichLyricLine>,
        track: TrackIdentity
    ): List<RichLyricLine> {
        val withoutPromo = lines.filter { line ->
            !KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine(line.text) &&
                !line.text.isNullOrBlank()
        }
        if (withoutPromo.size <= 1) return withoutPromo
        val first = withoutPromo.first()
        return if (first.begin <= 1_000L &&
            KuGouTrackIdentity.looksLikeMetadataLead(first.text, track.title, track.artist)
        ) {
            withoutPromo.drop(1)
        } else {
            withoutPromo
        }
    }

    private fun stripPromoLinesFromLrc(lyric: String): String {
        val kept = lyric.lineSequence()
            .filter { line ->
                val text = line.substringAfter(']', line)
                !KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine(text) &&
                    !KuGouConceptLyricSanitizePolicy.shouldExcludeTimedPromoLine(line)
            }
            .joinToString("\n")
        return if (lyric.endsWith("\n")) kept + "\n" else kept
    }

    private fun toEnhancedLrc(track: TrackIdentity, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, track)
        lines.forEach { line ->
            val words = line.words.orEmpty().filter { !it.text.isNullOrEmpty() }
            if (words.isEmpty()) {
                appendTimedLine(builder, line.begin, line.text.orEmpty())
                return@forEach
            }
            builder.append('[').append(formatLrcTime(line.begin)).append(']')
            var previousTime = line.begin
            var lastWordEnd = line.begin
            words.forEachIndexed { index, word ->
                var wordTime = word.begin.coerceAtLeast(line.begin)
                if (wordTime < previousTime) {
                    val span = (line.duration.coerceAtLeast(line.end - line.begin))
                        .coerceIn(360L, 12_000L)
                    wordTime = line.begin + (span / words.size).coerceAtLeast(80L) * index
                }
                builder.append('<')
                    .append(formatLrcTime(wordTime))
                    .append('>')
                    .append(cleanInlineSegment(word.text.orEmpty()))
                previousTime = wordTime
                lastWordEnd = word.end.coerceAtLeast(wordTime)
            }
            val lineEnd = maxOf(line.end, lastWordEnd)
            if (lineEnd > line.begin) {
                builder.append('<').append(formatLrcTime(lineEnd)).append('>')
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun toPlainLrc(track: TrackIdentity, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, track)
        lines.forEach { line -> appendTimedLine(builder, line.begin, line.text.orEmpty()) }
        return builder.toString()
    }

    private fun toTranslationLrc(lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        lines.forEach { line ->
            val translation = line.secondary
            if (!translation.isNullOrBlank() && isUsableTranslation(translation, line.text)) {
                appendTimedLine(builder, line.begin, translation)
            }
        }
        return builder.toString()
    }

    private fun appendMetadata(builder: StringBuilder, track: TrackIdentity) {
        val title = cleanPlainText(track.title.orEmpty())
        val artist = cleanPlainText(track.artist.orEmpty())
        if (title.isNotBlank()) builder.append("[ti:").append(title).append("]\n")
        if (artist.isNotBlank()) builder.append("[ar:").append(artist).append("]\n")
    }

    private fun appendTimedLine(builder: StringBuilder, timeMillis: Long, text: String) {
        val clean = cleanPlainText(text)
        if (clean.isBlank()) return
        builder.append('[').append(formatLrcTime(timeMillis)).append(']')
            .append(clean).append('\n')
    }

    private fun isUsableTranslation(translation: String, primary: String?): Boolean {
        val clean = cleanPlainText(translation)
        return clean.isNotBlank() && clean != "//" && clean != cleanPlainText(primary.orEmpty())
    }

    private fun cleanInlineSegment(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ')

    private fun cleanPlainText(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').replace(WHITESPACE_REGEX, " ").trim()

    private fun containsTimedLrc(value: String): Boolean = TIMED_LRC_REGEX.containsMatchIn(value)

    private fun formatLrcTime(ms: Long): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(min, sec, millis)
    }

    private fun jsonQuote(value: String): String = "\"" + escapeJson(value) + "\""

    private fun escapeJson(value: String): String = buildString(value.length + 16) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    internal fun extractJsonString(json: String, key: String): String? {
        if (json.isBlank()) return null
        JSON_STRING_FIELD.findAll(json).forEach { match ->
            if (match.groupValues[1] == key) {
                return unescapeJson(match.groupValues[2])
            }
        }
        return null
    }

    internal fun extractJsonRaw(json: String, key: String): String? {
        if (json.isBlank()) return null
        val pattern = Regex(""""$key"\s*:\s*([^,}\]]+)""")
        return pattern.find(json)?.groupValues?.get(1)?.trim()
    }

    private fun unescapeJson(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.lastIndex) {
                append(character)
                index += 1
                continue
            }
            when (value[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '"' -> append('"')
                '\\' -> append('\\')
                '/' -> append('/')
                else -> append(value[index + 1])
            }
            index += 2
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
