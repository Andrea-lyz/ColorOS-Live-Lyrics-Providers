/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import io.github.proify.extensions.bridge.LrcTimeFormatter
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/**
 * Builds the lyricInfo object that KuWo's own MediaSession publishes to ColorOS SystemUI.
 * The Provider owns this payload; it is deliberately independent from the Bridge's external
 * lyric protocol so a 3.7.3 Bridge can consume it through the normal native media path.
 */
object KuWoOfficialLyricInfoEncoder {
    const val METADATA_KEY_LYRIC_INFO = "lyricInfo"

    // This payload is written by KuWo's own MediaSession path. Do not mark it as the Bridge
    // module envelope: Bridge uses that marker to enter the external-provider handoff path,
    // which can make SystemUI rebuild the lyric surface while the same-track list is transiently
    // empty and switch the media card back to its solid-color album-art state.
    private const val PROVIDER = "cn.kuwo.player"
    // Keep the source value used by the former Bridge-side native encoder. It is metadata
    // provenance, not an external-provider broadcast identity.
    private const val SOURCE = "kuwo-internal"
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")

    data class Encoded(
        val value: String,
        val plainLyric: String,
        val rawLyric: String,
        val translationLyric: String
    )

    fun encode(song: Song?, trackGeneration: Long): Encoded? {
        if (song == null) return null
        val lines = song.lyrics.orEmpty()
            .filter { !it.text.isNullOrBlank() }
            .sortedBy { it.begin }
        if (lines.isEmpty()) return null

        val plainLyric = toPlainLrc(song, lines)
        if (plainLyric.isBlank()) return null
        val rawLyric = toEnhancedLrc(song, lines)
        val translationLyric = toTranslationLrc(song, lines)
        val fields = mutableListOf(
            jsonStringField("songName", song.name.orEmpty()),
            jsonStringField("artist", song.artist.orEmpty()),
            jsonStringField("songId", song.id.orEmpty()),
            "\"lyricType\":0",
            // KuWo's native encoder leaves the lyric-record id empty; songId carries the
            // MediaSession track identity. Keep that distinction instead of reusing rid twice.
            jsonStringField("id", ""),
            jsonStringField("lyric", plainLyric),
            "\"noLyric\":false",
            jsonStringField("provider", PROVIDER),
            jsonStringField("source", SOURCE),
            jsonStringField("trackKey", TrackKey.key(song.name, song.artist)),
            "\"sessionGeneration\":" + trackGeneration
        )
        if (containsTimedLrc(rawLyric)) fields += jsonStringField("rawLyric", rawLyric)
        if (containsTimedLrc(translationLyric)) {
            fields += jsonStringField("translationLyric", translationLyric)
        }
        return Encoded(
            value = fields.joinToString(prefix = "{", postfix = "}"),
            plainLyric = plainLyric,
            rawLyric = rawLyric,
            translationLyric = translationLyric
        )
    }

    private fun toEnhancedLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        lines.forEach { line ->
            val words = line.words.orEmpty().filter { !it.text.isNullOrEmpty() }
            if (words.isEmpty()) {
                appendTimedLine(builder, line.begin, line.text.orEmpty())
                return@forEach
            }
            builder.append('[').append(LrcTimeFormatter.format(line.begin)).append(']')
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
                    .append(LrcTimeFormatter.format(wordTime))
                    .append('>')
                    .append(cleanInlineSegment(word.text.orEmpty()))
                previousTime = wordTime
                lastWordEnd = word.end.coerceAtLeast(wordTime)
            }
            val lineEnd = maxOf(line.end, lastWordEnd)
            if (lineEnd > line.begin) {
                builder.append('<').append(LrcTimeFormatter.format(lineEnd)).append('>')
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun toPlainLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        lines.forEach { line -> appendTimedLine(builder, line.begin, line.text.orEmpty()) }
        return builder.toString()
    }

    private fun toTranslationLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        lines.forEach { line ->
            val translation = line.translation
            if (!translation.isNullOrBlank() && isUsableTranslation(translation, line.text)) {
                appendTimedLine(builder, line.begin, translation)
            }
        }
        return builder.toString()
    }

    private fun appendMetadata(builder: StringBuilder, song: Song) {
        val title = cleanPlainText(song.name.orEmpty())
        val artist = cleanPlainText(song.artist.orEmpty())
        if (title.isNotBlank()) builder.append("[ti:").append(title).append("]\n")
        if (artist.isNotBlank()) builder.append("[ar:").append(artist).append("]\n")
    }

    private fun appendTimedLine(builder: StringBuilder, timeMillis: Long, text: String) {
        val clean = cleanPlainText(text)
        if (clean.isBlank()) return
        builder.append('[').append(LrcTimeFormatter.format(timeMillis)).append(']')
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

    private fun jsonStringField(name: String, value: String): String =
        "\"" + escapeJson(name) + "\":\"" + escapeJson(value) + "\""

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

    private object TrackKey {
        fun key(title: String?, artist: String?): String {
            return listOf(title.orEmpty(), artist.orEmpty())
                .joinToString("|") { it.trim().lowercase().replace(WHITESPACE_REGEX, " ") }
        }
    }
}
