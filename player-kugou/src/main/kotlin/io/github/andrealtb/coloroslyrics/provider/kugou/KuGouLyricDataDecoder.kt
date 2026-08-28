/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

/**
 * Reads KuGou `LyricData` by public getters first, then Lite obfuscated names,
 * then typed fields. Romaji / transliteration is captured only so callers can
 * ignore it; it is never treated as translation.
 *
 * `LyricManager` load returns a wrapper, not `LyricData`. Only unwrap a field
 * whose runtime class is `com.kugou.framework.lyric.LyricData`. Do not probe
 * the wrapper with `decode()` — its `String` fields (`d`/`f`) are not matrices.
 */
object KuGouLyricDataDecoder {

    private val WORDS_METHODS = listOf("getWords", "z")
    private val WORDS_FIELDS = listOf("words", "f")
    private val ROW_BEGIN_METHODS = listOf("getRowBeginTime", "o")
    private val ROW_BEGIN_FIELDS = listOf("rowBeginTime", "d")
    private val ROW_DELAY_METHODS = listOf("getRowDelayTime", "p")
    private val ROW_DELAY_FIELDS = listOf("rowDelayTime", "e")
    private val WORD_BEGIN_METHODS = listOf("getWordBeginTime", "v")
    private val WORD_BEGIN_FIELDS = listOf("wordBeginTime", "i")
    private val WORD_DELAY_METHODS = listOf("getWordDelayTime", "w")
    private val WORD_DELAY_FIELDS = listOf("wordDelayTime", "j")
    private val TRANSLATE_METHODS = listOf("getTranslateWords", "t")
    private val TRANSLATE_FIELDS = listOf("translateWords", "k")
    private val TRANSLIT_METHODS = listOf("getTransliterationWords", "u")
    private val TRANSLIT_FIELDS = listOf("transliterationWords", "l")

    fun lyricDataFromResult(result: Any?): Any? {
        if (result == null) return null
        if (isLyricDataClass(result)) return result
        findLyricDataField(result)?.let { return it }
        return findDecodableField(result)
    }

    fun decode(lyricData: Any?): List<RichLyricLine> {
        if (lyricData == null) return emptyList()
        return runCatching {
            val words = readStringMatrix(lyricData, WORDS_METHODS, WORDS_FIELDS) ?: return emptyList()
            val rowBeginTime = readLongArray(lyricData, ROW_BEGIN_METHODS, ROW_BEGIN_FIELDS)
                ?: return emptyList()
            val rowDelayTime = readLongArray(lyricData, ROW_DELAY_METHODS, ROW_DELAY_FIELDS)
                ?: LongArray(rowBeginTime.size)
            val wordBeginTime = readLongMatrix(lyricData, WORD_BEGIN_METHODS, WORD_BEGIN_FIELDS)
            val wordDelayTime = readLongMatrix(lyricData, WORD_DELAY_METHODS, WORD_DELAY_FIELDS)
            val translateWords = readStringMatrix(lyricData, TRANSLATE_METHODS, TRANSLATE_FIELDS)
            words.mapIndexedNotNull { index, rowWords ->
                val text = rowWords.joinToString(separator = "").trim()
                if (text.isBlank()) return@mapIndexedNotNull null
                val begin = rowBeginTime.getOrNull(index) ?: 0L
                val duration = rowDelayTime.getOrNull(index)
                    ?.takeIf { it > 0L }
                    ?: ((rowBeginTime.getOrNull(index + 1) ?: begin) - begin).takeIf { it > 0L }
                    ?: 0L
                val end = begin + duration
                RichLyricLine(
                    begin = begin,
                    end = end,
                    duration = duration,
                    text = text,
                    words = buildWords(
                        rowWords = rowWords,
                        lineBegin = begin,
                        lineEnd = end,
                        wordBegins = wordBeginTime?.getOrNull(index),
                        wordDurations = wordDelayTime?.getOrNull(index)
                    ).takeIf { it.isNotEmpty() },
                    secondary = rowText(translateWords, index)
                )
            }.sortedBy { it.begin }
        }.getOrDefault(emptyList())
    }

    private fun isLyricDataClass(value: Any): Boolean =
        value.javaClass.name == KuGouPlayerConstants.LYRIC_DATA_CLASS

    private fun findLyricDataField(holder: Any): Any? {
        return visitFields(holder) { value ->
            if (isLyricDataClass(value)) value else null
        }
    }

    private fun findDecodableField(holder: Any): Any? {
        return visitFields(holder) { value ->
            if (value is String || value is Number) {
                null
            } else if (decode(value).isNotEmpty()) {
                value
            } else {
                null
            }
        }
    }

    private fun visitFields(holder: Any, accept: (Any) -> Any?): Any? {
        return runCatching {
            var type: Class<*>? = holder.javaClass
            while (type != null && type != Any::class.java) {
                type.declaredFields.forEach { field ->
                    field.isAccessible = true
                    val value = field.get(holder) ?: return@forEach
                    accept(value)?.let { return@runCatching it }
                }
                type = type.superclass
            }
            null
        }.getOrNull()
    }

    private fun buildWords(
        rowWords: Array<String>,
        lineBegin: Long,
        lineEnd: Long,
        wordBegins: LongArray?,
        wordDurations: LongArray?
    ): List<LyricWord> {
        if (rowWords.isEmpty()) return emptyList()
        return rowWords.mapIndexedNotNull { index, rawWord ->
            val text = rawWord.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawBegin = wordBegins?.getOrNull(index) ?: 0L
            val begin = when {
                rawBegin in lineBegin..lineEnd -> rawBegin
                else -> lineBegin + rawBegin
            }.coerceAtLeast(lineBegin)
            val duration = wordDurations?.getOrNull(index)
                ?.takeIf { it > 0L }
                ?: wordBegins?.getOrNull(index + 1)?.let { next ->
                    val normalizedNext = if (next in lineBegin..lineEnd) next else lineBegin + next
                    (normalizedNext - begin).takeIf { it > 0L }
                }
                ?: 0L
            val end = if (duration > 0L) {
                (begin + duration).coerceAtMost(lineEnd.takeIf { it > lineBegin } ?: begin + duration)
            } else {
                begin
            }
            LyricWord(
                begin = begin,
                end = end,
                duration = duration,
                text = text
            )
        }
    }

    private fun rowText(rows: Array<Array<String>>?, index: Int): String? {
        return rows?.getOrNull(index)
            ?.joinToString(separator = "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "//" }
    }

    private fun readStringMatrix(
        lyricData: Any,
        methods: List<String>,
        fields: List<String>
    ): Array<Array<String>>? = coerceStringMatrix(readRaw(lyricData, methods, fields))

    private fun readLongMatrix(
        lyricData: Any,
        methods: List<String>,
        fields: List<String>
    ): Array<LongArray>? = coerceLongMatrix(readRaw(lyricData, methods, fields))

    private fun readLongArray(
        lyricData: Any,
        methods: List<String>,
        fields: List<String>
    ): LongArray? = coerceLongArray(readRaw(lyricData, methods, fields))

    private fun readRaw(
        lyricData: Any,
        methods: List<String>,
        fields: List<String>
    ): Any? {
        methods.forEach { name ->
            runCatching {
                lyricData.javaClass.getMethod(name).invoke(lyricData)
            }.getOrNull()?.let { return it }
        }
        fields.forEach { name ->
            runCatching {
                val field = lyricData.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.get(lyricData)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun coerceStringMatrix(raw: Any?): Array<Array<String>>? {
        if (raw == null || raw is String) return null
        val rows = raw as? Array<*> ?: return null
        if (rows.isEmpty()) return emptyArray()
        if (rows[0] is String) return null
        return Array(rows.size) { index ->
            when (val row = rows[index]) {
                is Array<*> -> Array(row.size) { cell -> row[cell]?.toString().orEmpty() }
                else -> emptyArray()
            }
        }
    }

    private fun coerceLongMatrix(raw: Any?): Array<LongArray>? {
        if (raw == null || raw is String) return null
        val rows = raw as? Array<*> ?: return null
        return Array(rows.size) { index ->
            coerceLongArray(rows[index]) ?: LongArray(0)
        }
    }

    private fun coerceLongArray(raw: Any?): LongArray? {
        return when (raw) {
            null, is String -> null
            is LongArray -> raw
            is Array<*> -> LongArray(raw.size) { index ->
                (raw[index] as? Number)?.toLong() ?: 0L
            }
            else -> null
        }
    }
}
