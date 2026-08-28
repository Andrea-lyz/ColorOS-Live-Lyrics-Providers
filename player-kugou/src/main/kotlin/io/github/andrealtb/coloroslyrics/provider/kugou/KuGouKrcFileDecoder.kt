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
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.io.File

object KuGouKrcFileDecoder {
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
        val lines = document.richLyricLines.filter { !it.text.isNullOrBlank() }
        if (lines.isEmpty()) return emptyList()
        return lines.map { line ->
            val translation = line.secondary
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "//" && it != line.text.orEmpty().trim() }
            line.copy(secondary = translation)
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

}
