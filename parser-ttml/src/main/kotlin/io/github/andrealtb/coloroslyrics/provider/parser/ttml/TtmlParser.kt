/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.ttml

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.LatinSyllableSpanMerger
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parses TTML (W3C, BetterLyrics, Apple TTML) into plain LRC, enhanced LRC, and structured RichLyricLine list.
 */
object TtmlParser {
    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

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

    fun parse(ttml: String?): TtmlResult? {
        if (ttml.isNullOrBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
            val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
            val globalOffsetMs = findTtmlLyricOffsetMillis(doc)

            val lines = mutableListOf<TtmlLineInfo>()
            val pNodes = doc.getElementsByTagNameNS("*", "p")

            for (i in 0 until pNodes.length) {
                val p = pNodes.item(i) as? Element ?: continue
                val begin = timingAttribute(p, "begin").ifEmpty { findFirstSpanBegin(p).orEmpty() }
                if (begin.isEmpty()) continue
                val lineMs = applyTtmlOffset(parseTTMLTime(begin), globalOffsetMs)
                if (lineMs < 0) continue

                val spans = mutableListOf<TtmlWordInfo>()
                var child = p.firstChild
                while (child != null) {
                    if (child is Element) {
                        val name = child.localName ?: child.nodeName.substringAfterLast(':')
                        if (name == "span") {
                            val role = child.getAttribute("role").ifEmpty { child.getAttribute("ttm:role") }
                            if (role != "x-bg" && role != "x-translation" && role != "x-roman") {
                                val spanText = child.textContent ?: ""
                                val spanBegin = timingAttribute(child, "begin")
                                val spanEnd = timingAttribute(child, "end")
                                val next = child.nextSibling
                                val separator = if (next?.nodeType == Node.TEXT_NODE) {
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

            val plainLrc = sorted.joinToString("\n") { line ->
                "${formatLrcTime(line.startMs)}${line.text}"
            }

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

            val richLines = sorted.map { line ->
                val lineWords = line.words.map { w ->
                    LyricWord(
                        begin = w.startMs,
                        end = w.endMs,
                        duration = (w.endMs - w.startMs).coerceAtLeast(0L),
                        text = w.text
                    )
                }
                val endMs = lineWords.lastOrNull()?.end ?: (line.startMs + 5000L)
                RichLyricLine(
                    begin = line.startMs,
                    end = endMs,
                    duration = (endMs - line.startMs).coerceAtLeast(0L),
                    text = line.text,
                    words = lineWords.takeIf { it.isNotEmpty() }
                )
            }

            TtmlResult(plainLrc, enhancedLrc.trimEnd(), richLines)
        } catch (_: Exception) {
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

    private fun findFirstSpanBegin(p: Element): String? {
        var child = p.firstChild
        while (child != null) {
            if (child is Element) {
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

    private fun timingAttribute(element: Element, name: String): String {
        val direct = element.getAttribute(name)
        if (direct.isNotEmpty()) return direct
        return element.getAttributeNS(TTML_PARAMETER_NS, name)
    }

    private fun mergeTtmlLatinSyllableSpans(
        spans: List<TtmlWordInfo>
    ): List<TtmlWordInfo> {
        if (spans.size < 2) return spans
        return LatinSyllableSpanMerger.merge(
            spans.map { span ->
                LatinSyllableSpanMerger.Span(
                    text = span.text,
                    begin = span.startMs,
                    end = span.endMs,
                    hasTrailingSpace = span.hasTrailingSpace
                )
            }
        ).map { span ->
            TtmlWordInfo(
                text = span.text,
                startMs = span.begin,
                endMs = span.end,
                hasTrailingSpace = span.hasTrailingSpace
            )
        }
    }

    private fun findTtmlLyricOffsetMillis(doc: Document): Long {
        val audioNodes = doc.getElementsByTagNameNS("*", "audio")
        for (index in 0 until audioNodes.length) {
            val audio = audioNodes.item(index) as? Element ?: continue
            val offsetSeconds = audio.getAttribute("lyricOffset").toDoubleOrNull() ?: continue
            return (offsetSeconds * 1_000.0).toLong()
        }
        return 0L
    }

    private fun applyTtmlOffset(timeMs: Long, offsetMs: Long): Long {
        if (timeMs < 0L) return -1L
        return (timeMs + offsetMs).coerceAtLeast(0L)
    }

    private fun extractDirectText(p: Element): String {
        val sb = StringBuilder()
        var child = p.firstChild
        while (child != null) {
            if (child.nodeType == Node.TEXT_NODE) sb.append(child.textContent)
            else if (child is Element) {
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
