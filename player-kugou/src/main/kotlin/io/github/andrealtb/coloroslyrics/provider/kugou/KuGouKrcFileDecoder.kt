/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.parser.krc.KrcDecryptor
import io.github.andrealtb.coloroslyrics.provider.parser.krc.KrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.krc.language.LanguageParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.LrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.io.File

object KuGouKrcFileDecoder {
    private val WORD_TAG_PATTERN = Regex("""<(\d+)\s*,\s*(\d+)\s*,\s*(\d+)>""")

    fun decodeFile(file: File): List<RichLyricLine> {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return when (file.extension.lowercase()) {
            "krc" -> decodeKrcBytes(file.readBytes())
            "lyc" -> decodeKrcBytes(file.readBytes()).ifEmpty {
                decodeLrcText(runCatching { file.readText() }.getOrDefault(""))
            }
            "lrc", "txt" -> decodeLrcText(file.readText())
            else -> emptyList()
        }
    }

    fun decodeKrcBytes(raw: ByteArray): List<RichLyricLine> {
        val decrypted = KrcDecryptor.decrypt(raw) ?: return emptyList()
        return decodeDecryptedKrc(decrypted)
    }

    fun decodeDecryptedKrc(decrypted: String): List<RichLyricLine> {
        val document = KrcParser.parse(decrypted)
        val lines = document.lines.filter { !it.text.isNullOrBlank() }
        if (lines.isEmpty()) return emptyList()
        val translates = document.language
            ?.let { LanguageParser.parse(it) }
            ?.getTranslate()
            ?.flatten()
            .orEmpty()
        val bodies = krcTimedBodies(decrypted)
        return lines.mapIndexed { index, line ->
            val words = parseKrcWords(bodies.getOrNull(index), line.begin)
            val translation = translates.getOrNull(index)
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "//" && it != line.text.orEmpty().trim() }
            RichLyricLine(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                text = line.text,
                words = words.takeIf { it.isNotEmpty() },
                secondary = translation
            )
        }
    }

    private fun decodeLrcText(raw: String): List<RichLyricLine> {
        if (raw.isBlank()) return emptyList()
        return LrcParser.parse(raw).lines
            .filter { !it.text.isNullOrBlank() }
            .map { line ->
                RichLyricLine(
                    begin = line.begin,
                    end = line.end,
                    duration = line.duration,
                    text = line.text
                )
            }
    }

    private fun krcTimedBodies(decrypted: String): List<String> =
        decrypted.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[") && it.contains("<") }
            .toList()

    internal fun parseKrcWords(lineBody: String?, lineBegin: Long): List<LyricWord> {
        if (lineBody.isNullOrBlank()) return emptyList()
        val matches = WORD_TAG_PATTERN.findAll(lineBody).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val offset = match.groupValues[1].toLongOrNull() ?: 0L
            val duration = match.groupValues[2].toLongOrNull() ?: 0L
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: lineBody.length
            val text = lineBody.substring(textStart, textEnd)
            if (text.isEmpty()) return@mapIndexedNotNull null
            val begin = lineBegin + offset
            LyricWord(
                begin = begin,
                end = begin + duration,
                duration = duration,
                text = text
            )
        }
    }
}
