/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import java.util.Locale
import java.util.regex.Pattern

object ConeLyricFilter {

    private val TIMED_LRC_TAG = Pattern.compile("[\\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\\]>]")
    private val HEADER_TAG_REGEX = Regex("""^\[[a-zA-Z]{2,8}:.*]""")

    fun isTimedLrc(lyric: String?): Boolean {
        if (lyric.isNullOrBlank()) return false
        return TIMED_LRC_TAG.matcher(lyric).find()
    }

    fun extractVisibleText(lyric: String?): String {
        if (lyric == null) return ""
        val normalized = lyric.replace("\r\n", "\n").replace('\r', '\n')
        val text = StringBuilder()
        for (line in normalized.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || HEADER_TAG_REGEX.matches(trimmed)) {
                continue
            }
            val visible = TIMED_LRC_TAG.matcher(trimmed).replaceAll("").trim()
            if (visible.isNotEmpty()) {
                if (text.isNotEmpty()) {
                    text.append('\n')
                }
                text.append(visible)
            }
        }
        return removeIgnorableCharacters(text.toString()).trim()
    }

    fun isNoLyricPlaceholder(lyric: String?): Boolean {
        val text = extractVisibleText(lyric)
        if (text.isEmpty()) return false
        val normalized = text.lowercase(Locale.ROOT)
        return ConePlayerConstants.EMPTY_LYRIC_TEXTS.contains(normalized)
    }

    fun isUsableTimedLyric(lyric: String?): Boolean {
        if (!isTimedLrc(lyric)) return false
        val visible = extractVisibleText(lyric)
        if (visible.isBlank()) return false
        return !isNoLyricPlaceholder(lyric)
    }

    private fun removeIgnorableCharacters(source: String): String {
        val sb = StringBuilder(source.length)
        for (c in source) {
            if (c != '\u200B' && c != '\u200C' && c != '\u200D' && c != '\uFEFF' && c != '\u00AD') {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
