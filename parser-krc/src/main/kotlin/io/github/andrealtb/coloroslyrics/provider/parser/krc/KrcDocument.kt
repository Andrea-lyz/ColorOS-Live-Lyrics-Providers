/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc

import io.github.andrealtb.coloroslyrics.provider.parser.krc.language.LanguageParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Serializable
data class KrcDocument(
    val metadata: Map<String, String> = emptyMap(),
    val lines: List<RichLyricLine> = emptyList()
) {

    val richLyricLines: List<RichLyricLine> by lazy {
        val languageInfo = language?.let { LanguageParser.parse(it) }
        val translates = languageInfo?.getTranslate()?.flatten() ?: emptyList()

        lines.mapIndexed { index, line ->
            line.copy(
                secondary = if (lines.size == translates.size) translates.getOrNull(index) else null
            )
        }
    }

    val language: String? by lazy {
        val key = metadata.keys.find {
            it.equals("language", ignoreCase = true)
        }
        if (key.isNullOrBlank()) return@lazy null
        val value = metadata[key]
        if (value.isNullOrBlank()) return@lazy null
        val decode = runCatching {
            val decoded = Base64.decode(value)
            String(decoded, Charsets.UTF_8)
        }.getOrNull() ?: value

        decode
    }

    fun applyOffset(offsetMs: Long): KrcDocument {
        if (offsetMs == 0L) return this

        val newLines = lines.map { line ->
            val newBegin = (line.begin + offsetMs).coerceAtLeast(0L)
            val newEnd = newBegin + line.duration
            val newWords = line.words?.map { word ->
                val begin = (word.begin + offsetMs).coerceAtLeast(0L)
                val end = (word.end + offsetMs).coerceAtLeast(begin)
                word.copy(begin = begin, end = end, duration = end - begin)
            }
            line.copy(
                begin = newBegin,
                end = newEnd,
                duration = line.duration,
                words = newWords
            )
        }
        return copy(lines = newLines)
    }
}
