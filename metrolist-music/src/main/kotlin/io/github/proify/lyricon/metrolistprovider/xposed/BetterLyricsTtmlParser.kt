/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import android.util.Log

/** Converts BetterLyrics TTML into plain and canonical enhanced LRC. */
internal object BetterLyricsTtmlParser {
    private const val TAG = "MetrolistProvider"
    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

    /** Result of TTML parsing: plain LRC + enhanced LRC with word-level timing. */
    internal data class TTMLResult(val plainLrc: String, val enhancedLrc: String)

    private data class TtmlWordInfo(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val hasTrailingSpace: Boolean
    )

    private data class TtmlLineInfo(
        val startMs: Long,
        val text: String,
        val words: List<TtmlWordInfo>
    )

    /**
     * Parses TTML from BetterLyrics into both plain LRC and enhanced LRC
     * (with same-line word timing in [line]<word-time>text... format).
     */
    internal fun parseTTML(ttml: String): TTMLResult? {
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
            val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
            val globalOffsetMs = findTtmlLyricOffsetMillis(doc)

            val lines = mutableListOf<TtmlLineInfo>()
            val pNodes = doc.getElementsByTagNameNS("*", "p")

            for (i in 0 until pNodes.length) {
                val p = pNodes.item(i) as? org.w3c.dom.Element ?: continue
                val begin = timingAttribute(p, "begin").ifEmpty { findFirstSpanBegin(p) }
                if (begin.isNullOrEmpty()) continue
                val lineMs = applyTtmlOffset(parseTTMLTime(begin), globalOffsetMs)
                if (lineMs < 0) continue

                // Extract words from <span> children with timing and spacing info
                val spans = mutableListOf<TtmlWordInfo>()
                var child = p.firstChild
                while (child != null) {
                    if (child is org.w3c.dom.Element) {
                        val name = child.localName ?: child.nodeName.substringAfterLast(':')
                        if (name == "span") {
                            val role = child.getAttribute("role").ifEmpty { child.getAttribute("ttm:role") }
                            if (role != "x-bg" && role != "x-translation" && role != "x-roman") {
                                val spanText = child.textContent ?: ""
                                val spanBegin = timingAttribute(child, "begin")
                                val spanEnd = timingAttribute(child, "end")
                                // Detect trailing space: text ends with whitespace OR next text node starts with whitespace
                                val next = child.nextSibling
                                val separator = if (next?.nodeType == org.w3c.dom.Node.TEXT_NODE) {
                                    next.textContent.orEmpty()
                                } else {
                                    ""
                                }
                                val hasInlineSeparator = separator.isNotEmpty() &&
                                    !separator.contains('\n') &&
                                    !separator.contains('\r') &&
                                    separator.first().isWhitespace()
                                val hasTrailing = spanText.lastOrNull()?.isWhitespace() == true ||
                                    hasInlineSeparator
                                val wStart = if (spanBegin.isNotEmpty()) {
                                    applyTtmlOffset(parseTTMLTime(spanBegin), globalOffsetMs)
                                } else {
                                    lineMs
                                }
                                val wEnd = if (spanEnd.isNotEmpty()) {
                                    applyTtmlOffset(parseTTMLTime(spanEnd), globalOffsetMs)
                                } else {
                                    wStart + 500
                                }
                                val trimmed = spanText.trim()
                                if (trimmed.isNotEmpty()) {
                                    spans.add(TtmlWordInfo(trimmed, wStart, wEnd, hasTrailing))
                                }
                            }
                        }
                    }
                    child = child.nextSibling
                }

                // BetterLyrics/Apple TTML uses an explicit whitespace text node between
                // Latin words. Adjacent Latin spans without one are syllables of the same
                // word; collapse those spans so Bridge's enhanced-LRC parser does not insert
                // a display space between every timed syllable.
                val words = mergeTtmlLatinSyllableSpans(spans)
                val lineText = if (words.isNotEmpty()) {
                    buildString {
                        words.forEachIndexed { idx, w ->
                            append(w.text)
                            if (w.hasTrailingSpace && idx < words.lastIndex) append(' ')
                        }
                    }.trim()
                } else {
                    extractDirectText(p).trim()
                }

                if (lineText.isNotEmpty()) {
                    lines.add(TtmlLineInfo(lineMs, lineText, words))
                }
            }

            if (lines.isEmpty()) return null

            val sorted = lines.sortedBy { it.startMs }

            // Plain LRC
            val plainLrc = sorted.joinToString("\n") { line ->
                "${formatLrcTime(line.startMs)}${line.text}"
            }

            // Enhanced LRC with word-level timing.  Keep the word timestamps on the same line as
            // the lyric text; this is the format consumed by both Lyricon and the Bridge parser.
            // The old implementation emitted a separate `<word:start:end|...>` line, which is
            // neither standard enhanced LRC nor parseable by the Bridge and was discarded by
            // sanitizeExtendedLrc().
            val enhancedLrc = buildString {
                for (line in sorted) {
                    append(formatLrcTime(line.startMs))
                    if (line.words.isNotEmpty()) {
                        line.words.forEach { word ->
                            append('<').append(formatInlineTime(word.startMs)).append('>')
                            append(word.text)
                            if (word.hasTrailingSpace) append(' ')
                        }
                        val finalEnd = line.words.last().endMs
                            .coerceAtLeast(line.words.last().startMs)
                        append('<').append(formatInlineTime(finalEnd)).append('>')
                    } else {
                        append(line.text)
                    }
                    append('\n')
                }
            }

            TTMLResult(plainLrc, enhancedLrc.trimEnd())
        } catch (e: Exception) {
            Log.d(TAG, "TTML parse failed: ${e.message}")
            null
        }
    }

    internal fun formatLrcTime(ms: Long): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        val millis = ms % 1000
        return "[%02d:%02d.%03d]".format(min, sec, millis)
    }

    internal fun formatInlineTime(ms: Long): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(min, sec, millis)
    }

    private fun findFirstSpanBegin(p: org.w3c.dom.Element): String? {
        var child = p.firstChild
        while (child != null) {
            if (child is org.w3c.dom.Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    val b = timingAttribute(child, "begin")
                    if (b.isNotEmpty()) return b
                }
            }
            child = child.nextSibling
        }
        return null
    }

    private fun timingAttribute(element: org.w3c.dom.Element, name: String): String {
        val direct = element.getAttribute(name)
        if (direct.isNotEmpty()) return direct
        return element.getAttributeNS(TTML_PARAMETER_NS, name)
    }

    private fun mergeTtmlLatinSyllableSpans(
        spans: List<TtmlWordInfo>
    ): List<TtmlWordInfo> {
        if (spans.size < 2) return spans
        val merged = mutableListOf<TtmlWordInfo>()
        var pending: TtmlWordInfo? = null

        fun flushPending() {
            pending?.let(merged::add)
            pending = null
        }

        spans.forEach { span ->
            if (!containsAsciiLetterOrDigit(span.text)) {
                flushPending()
                merged.add(span)
                return@forEach
            }

            pending = pending?.let { current ->
                current.copy(
                    text = current.text + span.text,
                    endMs = span.endMs.coerceAtLeast(current.endMs),
                    hasTrailingSpace = span.hasTrailingSpace
                )
            } ?: span

            if (span.hasTrailingSpace) {
                flushPending()
            }
        }
        flushPending()
        return merged
    }

    private fun containsAsciiLetterOrDigit(value: String): Boolean {
        return value.any { character ->
            character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9'
        }
    }

    private fun findTtmlLyricOffsetMillis(doc: org.w3c.dom.Document): Long {
        val audioNodes = doc.getElementsByTagNameNS("*", "audio")
        for (index in 0 until audioNodes.length) {
            val audio = audioNodes.item(index) as? org.w3c.dom.Element ?: continue
            val offsetSeconds = audio.getAttribute("lyricOffset").toDoubleOrNull() ?: continue
            return (offsetSeconds * 1_000.0).toLong()
        }
        return 0L
    }

    private fun applyTtmlOffset(timeMs: Long, offsetMs: Long): Long {
        if (timeMs < 0L) return -1L
        return (timeMs + offsetMs).coerceAtLeast(0L)
    }

    private fun extractDirectText(p: org.w3c.dom.Element): String {
        val sb = StringBuilder()
        var child = p.firstChild
        while (child != null) {
            if (child.nodeType == org.w3c.dom.Node.TEXT_NODE) sb.append(child.textContent)
            else if (child is org.w3c.dom.Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                val role = child.getAttribute("role").ifEmpty { child.getAttribute("ttm:role") }
                if (name == "span" && role != "x-bg" && role != "x-translation" && role != "x-roman") {
                    sb.append(child.textContent)
                }
            }
            child = child.nextSibling
        }
        return sb.toString()
    }

    private fun parseTTMLTime(time: String): Long {
        // Formats: HH:MM:SS.mmm, MM:SS.mmm, SS.mmm, 1.5s, 2000ms, 2m, 1h.
        return try {
            val value = time.trim()
            if (value.endsWith("ms", ignoreCase = true)) {
                return value.dropLast(2).toDouble().toLong()
            }
            if (!value.contains(':')) {
                val multiplier = when {
                    value.endsWith("h", ignoreCase = true) -> 3_600_000.0
                    value.endsWith("m", ignoreCase = true) -> 60_000.0
                    else -> 1_000.0
                }
                val numeric = if (value.lastOrNull()?.isLetter() == true) {
                    value.dropLast(1)
                } else {
                    value
                }
                return (numeric.toDouble() * multiplier).toLong()
            }
            val parts = value.split(":")
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    (h * 3600000 + m * 60000 + (s * 1000).toLong())
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    (m * 60000 + (s * 1000).toLong())
                }
                1 -> (parts[0].toDouble() * 1000).toLong()
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
