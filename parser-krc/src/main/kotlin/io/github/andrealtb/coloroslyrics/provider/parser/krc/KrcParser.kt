/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricLine
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import java.util.regex.Pattern

object KrcParser {

    private val LINE_PATTERN = Pattern.compile("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
    private val META_PATTERN = Pattern.compile("""^\[([a-zA-Z0-9_]+)\s*:(.*)$""")
    private val WORD_TAG_PATTERN = Pattern.compile("""<(\d+)\s*,\s*(\d+)\s*,\s*(\d+)>""")

    fun parse(content: String?): KrcDocument {
        if (content.isNullOrBlank()) return KrcDocument(emptyMap(), emptyList())

        val metadata = mutableMapOf<String, String>()
        val lines = mutableListOf<LyricLine>()

        var currentMetaKey: String? = null
        val currentMetaValue = StringBuilder()

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val lineMatcher = LINE_PATTERN.matcher(line)
            if (lineMatcher.matches()) {
                currentMetaKey = null

                val lineStart = lineMatcher.group(1).toLongOrNull() ?: 0L
                val lineDur = lineMatcher.group(2).toLongOrNull() ?: 0L
                val lineBody = lineMatcher.group(3).trim()

                if (lineBody.isNotEmpty()) {
                    runCatching {
                        lines.add(parseLineBody(lineStart, lineDur, lineBody))
                    }
                }
                return@forEach
            }

            val metaMatcher = META_PATTERN.matcher(line)
            if (metaMatcher.matches()) {
                val key = metaMatcher.group(1)
                if (key.all { it.isDigit() }) return@forEach

                var value = metaMatcher.group(2).trim()

                if (value.endsWith("]")) {
                    value = value.dropLast(1).trim()
                    metadata[key] = value
                    currentMetaKey = null
                } else {
                    currentMetaKey = key
                    currentMetaValue.clear()
                    currentMetaValue.append(value)
                }
                return@forEach
            }

            if (currentMetaKey != null) {
                if (line.endsWith("]")) {
                    currentMetaValue.append(line.dropLast(1).trim())
                    metadata[currentMetaKey!!] = currentMetaValue.toString()
                    currentMetaKey = null
                } else {
                    currentMetaValue.append(line)
                }
            }
        }

        return KrcDocument(metadata, lines.sortedBy { it.begin })
    }

    private fun parseLineBody(lineStart: Long, lineDur: Long, body: String): LyricLine {
        val words = mutableListOf<LyricWord>()
        val textBuilder = StringBuilder()

        val wordMatcher = WORD_TAG_PATTERN.matcher(body)
        var previousWordPrefixEnd = -1
        var previousOffset = 0L
        var previousDuration = 0L

        while (wordMatcher.find()) {
            val currentStart = wordMatcher.start()

            if (previousWordPrefixEnd != -1) {
                val text = body.substring(previousWordPrefixEnd, currentStart)
                val begin = lineStart + previousOffset
                words.add(
                    LyricWord(
                        begin = begin,
                        end = begin + previousDuration,
                        duration = previousDuration,
                        text = text
                    )
                )
                textBuilder.append(text)
            }

            previousOffset = wordMatcher.group(1).toLongOrNull() ?: 0L
            previousDuration = wordMatcher.group(2).toLongOrNull() ?: 0L
            previousWordPrefixEnd = wordMatcher.end()
        }

        if (previousWordPrefixEnd != -1 && previousWordPrefixEnd <= body.length) {
            val text = body.substring(previousWordPrefixEnd)
            val begin = lineStart + previousOffset
            words.add(
                LyricWord(
                    begin = begin,
                    end = begin + previousDuration,
                    duration = previousDuration,
                    text = text
                )
            )
            textBuilder.append(text)
        }

        val finalText = if (words.isEmpty()) {
            body.replace(Regex("""<\d+\s*,\s*\d+\s*,\s*\d+>"""), "")
        } else {
            textBuilder.toString()
        }

        return LyricLine(
            begin = lineStart,
            end = lineStart + lineDur,
            duration = lineDur,
            text = finalText
        )
    }
}
