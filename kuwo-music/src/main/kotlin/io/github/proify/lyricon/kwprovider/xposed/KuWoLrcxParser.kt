/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kwprovider.xposed

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.util.regex.Pattern

/**
 * KuWo LRCX 解析器。
 *
 * KuWo 的逐字格式（参考 j6/f）：
 * - 行时间标签 [mm:ss.xx]，xx 为百分秒（<=2 位时 *10 转毫秒）
 * - 逐字标签 <start,end[,duration]>，start/end 是相对行的偏移，
 *   按 \[kuwo:N] 标签的系数换算：c = N/10, d = N%10，
 *   wordStart = (s+e)/(c*2)，wordEnd = wordStart + (s-e)/(d*2)
 * - 无 \[kuwo:] 标签时退化为 start/end 直接当毫秒偏移（保守兜底）
 */
object KuWoLrcxParser {

    private val LINE_TIME_PATTERN =
        Pattern.compile("""\[(\d{1,2}):(\d{1,2})(\.\d{1,4})?\]""")
    private val WORD_PATTERN =
        Pattern.compile("""<(-?\d+),(-?\d+)(?:,-?\d+)?>""")
    private val KUWO_TAG_PATTERN =
        Pattern.compile("""\[kuwo:\s*(\S+)\s*\]""", Pattern.CASE_INSENSITIVE)

    /**
     * 解析 LRCX 文本为歌词行。失败时返回空列表，调用方决定降级。
     */
    fun parse(raw: String?): List<RichLyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val kuwoScale = extractKuwoScale(raw)
        val lines = mutableListOf<RichLyricLine>()
        raw.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[")) return@forEach
            val timeMatcher = LINE_TIME_PATTERN.matcher(trimmed)
            if (!timeMatcher.find() || timeMatcher.start() != 0) return@forEach

            val begin = toMillis(
                timeMatcher.group(1),
                timeMatcher.group(2),
                timeMatcher.group(3)
            )
            val body = trimmed.substring(timeMatcher.end()).trim()
            if (body.isEmpty()) return@forEach

            val words = parseWords(body, kuwoScale, begin)
            val text = WORD_PATTERN.matcher(body).replaceAll("").trim()
            if (text.isEmpty() && words.isEmpty()) return@forEach
            val end = words.maxOfOrNull { it.end } ?: (begin + 1_000L)
            lines.add(
                RichLyricLine(
                    begin = begin,
                    end = end,
                    duration = (end - begin).coerceAtLeast(0L),
                    text = text,
                    words = words
                )
            )
        }
        return clampLineEnds(attachTranslations(lines))
    }

    /**
     * KuWo bilingual LRCX keeps the translation of line N as a separate row
     * timestamped at line N+1. Official j6.f marks that earlier same-timestamp
     * row as the translation companion; Bridge same-timestamp grouping would
     * otherwise pin it onto the next primary.
     */
    internal fun attachTranslations(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.isEmpty()) return emptyList()
        val ordered = lines.sortedBy { it.begin }
        val merged = mutableListOf<RichLyricLine>()
        var pendingTranslation: String? = null
        var index = 0
        while (index < ordered.size) {
            val current = ordered[index]
            val next = ordered.getOrNull(index + 1)
            if (next != null &&
                current.begin == next.begin &&
                shouldAttachAsTranslation(current, next)
            ) {
                val translation = current.text
                if (merged.isNotEmpty()) {
                    val previous = merged.last()
                    if (previous.translation.isNullOrBlank() && !translation.isNullOrBlank()) {
                        merged[merged.lastIndex] = previous.copy(translation = translation)
                    }
                } else if (!translation.isNullOrBlank()) {
                    pendingTranslation = translation
                }
                index += 1
                continue
            }
            val withPending = if (pendingTranslation != null && current.translation.isNullOrBlank()) {
                current.copy(translation = pendingTranslation)
            } else {
                current
            }
            pendingTranslation = null
            merged.add(withPending)
            index += 1
        }
        return merged
    }

    /**
     * Some KuWo LRCX payloads carry word tags whose calculated end precedes their start.
     * The official parser treats these as instant-complete markers, not usable karaoke
     * ranges. Preserve the text timeline by falling back to line timing and clipping each
     * line at the next primary line so a malformed metadata row cannot swallow real lyrics.
     */
    private fun clampLineEnds(lines: List<RichLyricLine>): List<RichLyricLine> {
        val withoutInvalidWordTiming = lines.map { line ->
            val words = line.words.orEmpty()
            val hasUsableWordTiming = words.isNotEmpty() && words.any { it.end > it.begin }
            if (words.isEmpty() || hasUsableWordTiming) {
                line
            } else {
                line.copy(
                    end = line.begin,
                    duration = 0L,
                    words = null
                )
            }
        }
        return withoutInvalidWordTiming.mapIndexed { index, line ->
            val nextBegin = withoutInvalidWordTiming.getOrNull(index + 1)?.begin
            if (nextBegin != null && nextBegin > line.begin && (line.end > nextBegin || line.end <= line.begin)) {
                line.copy(
                    end = nextBegin,
                    duration = nextBegin - line.begin
                )
            } else {
                line
            }
        }
    }

    private fun shouldAttachAsTranslation(
        current: RichLyricLine,
        next: RichLyricLine
    ): Boolean {
        val currentHasWords = hasWordTimings(current)
        val nextHasWords = hasWordTimings(next)
        if (!currentHasWords && nextHasWords) return true
        if (currentHasWords && !nextHasWords) return false
        return containsCjk(current.text) && !containsCjk(next.text)
    }

    private fun hasWordTimings(line: RichLyricLine): Boolean =
        !line.words.isNullOrEmpty()

    private fun containsCjk(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.any { ch -> ch in '㐀'..'鿿' }
    }

    private data class KuwoScale(val c: Int, val d: Int)

    private fun extractKuwoScale(raw: String): KuwoScale {
        val matcher = KUWO_TAG_PATTERN.matcher(raw)
        if (matcher.find()) {
            val value = matcher.group(1).trim().toIntOrNull()
            if (value != null && value > 0) {
                return KuwoScale(c = value / 10, d = value % 10)
            }
        }
        // 缺省系数：c=1, d=1 时 wordStart=(s+e)/2, wordEnd=wordStart+(s-e)/2 = s
        // 实际 KuWo 歌词通常带 \[kuwo:] 标签；无标签时保守按毫秒直读。
        return KuwoScale(c = 0, d = 0)
    }

    private fun parseWords(
        body: String,
        scale: KuwoScale,
        lineBegin: Long
    ): List<LyricWord> {
        val matcher = WORD_PATTERN.matcher(body)
        val words = mutableListOf<LyricWord>()
        // 第一遍：收集所有标签的时间参数和位置
        data class TagInfo(val s: Long, val e: Long, val start: Int, val end: Int)
        val tags = mutableListOf<TagInfo>()
        while (matcher.find()) {
            val s = matcher.group(1).toLongOrNull() ?: continue
            val e = matcher.group(2).toLongOrNull() ?: continue
            tags.add(TagInfo(s, e, matcher.start(0), matcher.end(0)))
        }
        if (tags.isEmpty()) return words
        tags.forEachIndexed { index, tag ->
            val (beginOffset, endOffset) = if (scale.c > 0 && scale.d > 0) {
                val start = (tag.s + tag.e) / (scale.c * 2L)
                val end = start + (tag.s - tag.e) / (scale.d * 2L)
                start to end
            } else {
                tag.s to tag.e
            }
            val begin = (lineBegin + beginOffset).coerceAtLeast(lineBegin)
            val end = (lineBegin + endOffset).coerceAtLeast(begin)
            val textStart = tag.end
            val nextStart = if (index + 1 < tags.size) tags[index + 1].start else body.length
            val wordText = body.substring(textStart, nextStart).trim()
            if (wordText.isEmpty()) return@forEachIndexed
            words.add(
                LyricWord(
                    begin = begin,
                    end = end,
                    duration = (end - begin).coerceAtLeast(0L),
                    text = wordText
                )
            )
        }
        return words
    }

    private fun toMillis(mStr: String, sStr: String, fStr: String?): Long {
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val fraction = fStr?.removePrefix(".")
        val ms = when (fraction?.length) {
            null, 0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            3 -> fraction.toLong()
            else -> fraction.take(3).toLong()
        }
        return m * 60_000L + s * 1_000L + ms
    }
}
