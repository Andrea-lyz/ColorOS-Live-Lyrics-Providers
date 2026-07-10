/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

internal object PowerampBridgeTaggedLyricParser {
    private val timeTagRegex =
        Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val whitespaceRegex = Regex("\\s+")

    fun parse(rawLyric: String?): List<PowerampBridgeTaggedLine> {
        if (rawLyric.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<PowerampBridgeTaggedLine>()
        var hasInlineWordTiming = false
        rawLyric.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trim()
            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty() || matches.first().range.first != 0) return@forEach

            val begin = matches.first().toMillis()
            if (matches.size == 1) {
                val text = cleanText(line.substring(matches.first().range.last + 1))
                if (text.isBlank()) return@forEach
                val previous = lines.lastOrNull()
                if (previous != null && previous.begin == begin && previous.translation.isBlank()) {
                    previous.translation = text
                } else {
                    lines += PowerampBridgeTaggedLine(begin = begin, text = text)
                }
                return@forEach
            }

            hasInlineWordTiming = true
            val segments = matches.mapIndexedNotNull { index, match ->
                val segmentEnd = matches.getOrNull(index + 1)?.range?.first ?: line.length
                val text = line.substring(match.range.last + 1, segmentEnd)
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                if (text.isBlank()) null else PowerampBridgeTimedSegment(match.toMillis(), text)
            }
            val text = cleanText(segments.joinToString(separator = "") { it.text })
            if (text.isBlank()) return@forEach
            val trailingTag = matches.last()
            val trailingText = line.substring(trailingTag.range.last + 1)
            val explicitEnd = trailingTag.toMillis().takeIf {
                trailingText.isBlank() && it > begin
            } ?: -1L
            lines += PowerampBridgeTaggedLine(
                begin = begin,
                text = text,
                segments = segments,
                end = explicitEnd
            )
        }
        return if (hasInlineWordTiming) lines else emptyList()
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fraction = groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
            3 -> fraction.toLongOrNull() ?: 0L
            else -> 0L
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun cleanText(value: String): String {
        return whitespaceRegex.replace(value.replace('\r', ' ').replace('\n', ' '), " ").trim()
    }
}

internal data class PowerampBridgeTaggedLine(
    val begin: Long,
    val text: String,
    val segments: List<PowerampBridgeTimedSegment> = emptyList(),
    val end: Long = -1L,
    var translation: String = ""
)

internal data class PowerampBridgeTimedSegment(
    val begin: Long,
    val text: String
)
