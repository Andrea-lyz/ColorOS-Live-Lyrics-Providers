/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.util.Locale

/**
 * Patches QQ's own lyricInfo JSON. Official `id` / `songId` / `lyricType` /
 * `lyric` / `noLyric` stay; `rawLyric`, `translationLyric` and `transLyric`
 * are appended. Romaji must never enter the translation lane. This is not a
 * Bridge envelope.
 */
object QqOfficialLyricInfoEncoder {
    const val SOURCE = QqPlayerConstants.SOURCE_INTERNAL
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
        if (lines.isEmpty()) return null
        val plainLyric = toPlainLrc(track, lines)
        if (plainLyric.isBlank()) return null
        val rawLyric = toEnhancedLrc(track, lines)
        val translationLyric = toTranslationLrc(lines)
        val existing = existingLyricInfo?.trim().orEmpty()
        val officialLyric = extractJsonString(existing, "lyric")
            ?.takeIf { it.isNotBlank() }
            ?: plainLyric
        val songId = firstNonBlank(
            extractJsonString(existing, "songId"),
            extractJsonRaw(existing, "songId")?.trim('"'),
            track.id
        ).orEmpty()
        val officialId = extractJsonRaw(existing, "id") ?: "0"
        val lyricType = extractJsonRaw(existing, "lyricType") ?: "0"
        val noLyric = extractJsonRaw(existing, "noLyric") ?: "false"

        val fields = linkedMapOf(
            "id" to officialId,
            "songId" to jsonQuote(songId),
            "songName" to jsonQuote(
                firstNonBlank(extractJsonString(existing, "songName"), track.title).orEmpty()
            ),
            "artist" to jsonQuote(
                firstNonBlank(extractJsonString(existing, "artist"), track.artist).orEmpty()
            ),
            "lyricType" to lyricType,
            "lyric" to jsonQuote(officialLyric),
            "noLyric" to noLyric,
            "provider" to jsonQuote(hostPackage),
            "source" to jsonQuote(SOURCE),
            "sessionGeneration" to trackGeneration.toString()
        )
        if (containsTimedLrc(rawLyric)) {
            fields["rawLyric"] = jsonQuote(rawLyric)
        }
        if (containsTimedLrc(translationLyric)) {
            fields["translationLyric"] = jsonQuote(translationLyric)
            fields["transLyric"] = jsonQuote(translationLyric)
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
            var lastWordEnd = line.begin
            words.forEach { word ->
                val wordTime = word.begin.coerceAtLeast(0L)
                builder.append('<')
                    .append(formatLrcTime(wordTime))
                    .append('>')
                    .append(cleanInlineSegment(word.text.orEmpty()))
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
        return String.format(Locale.ROOT, "%02d:%02d.%03d", min, sec, millis)
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
