/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.Serializable

@Serializable
data class EnhanceLrcDocument(
    val metadata: Map<String, String> = emptyMap(),
    val lines: List<RichLyricLine> = emptyList(),
) {
    /**
     * 应用全局时间偏移。
     * @param offsetMs 偏移毫秒数
     * @return 新的 EnhanceLrcDocument
     */
    internal fun applyOffset(offsetMs: Long): EnhanceLrcDocument {
        val newLines = lines.map { line ->
            val newBegin = (line.begin + offsetMs).coerceAtLeast(0L)
            val newEnd = (line.end + offsetMs).coerceAtLeast(newBegin)
            val newWords = line.words?.map { word ->
                val begin = (word.begin + offsetMs).coerceAtLeast(0L)
                val end = (word.end + offsetMs).coerceAtLeast(begin)
                word.copy(begin = begin, end = end, duration = end - begin)
            }
            val newSecondaryWords = line.secondaryWords?.map { word ->
                val begin = (word.begin + offsetMs).coerceAtLeast(0L)
                val end = (word.end + offsetMs).coerceAtLeast(begin)
                word.copy(begin = begin, end = end, duration = end - begin)
            }
            line.copy(
                begin = newBegin,
                end = newEnd,
                duration = newEnd - newBegin,
                words = newWords,
                secondaryWords = newSecondaryWords
            )
        }
        return EnhanceLrcDocument(metadata, newLines)
    }
}
