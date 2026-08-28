/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.LatinSyllableSpanMerger
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

object AppleSongMapper {
    private val BACKING_VOCAL_TOKEN = Regex(
        "^(?:o+h+|a+h+|h+a+|woo+|whoa+|yeah+|hey+|la+|na+)[,.!?~…]*$",
        RegexOption.IGNORE_CASE
    )

    fun toRichLines(song: AppleSongModel): List<RichLyricLine> =
        song.lyrics.filter(::shouldKeepLeadLyricLine).map(::toRichLine)

    internal fun shouldKeepLeadLyricLine(line: AppleLyricLineModel): Boolean {
        val lead = AppleHtmlText.clean(line.htmlLineText)
        if (lead.isBlank()) return false

        val background = AppleHtmlText.clean(line.htmlBackgroundVocalsLineText)
        val hasLeadWords = line.words.any { word ->
            !word.whitespace && AppleHtmlText.clean(word.text).isNotBlank()
        }
        val hasBackgroundWords = line.backgroundWords.any { word ->
            !word.whitespace && AppleHtmlText.clean(word.text).isNotBlank()
        }

        if (!hasLeadWords && (hasBackgroundWords || background.isNotBlank())) {
            return false
        }
        if (sameNormalizedLyric(lead, background)) {
            return false
        }
        if (hasBackgroundWords && isShortBackingVocal(lead)) {
            return false
        }
        return true
    }

    private fun toRichLine(line: AppleLyricLineModel): RichLyricLine {
        val words = karaokeWords(line.words)
        return RichLyricLine(
            begin = line.begin.toLong(),
            end = line.end.toLong(),
            duration = line.duration.toLong().coerceAtLeast(
                (line.end - line.begin).toLong().coerceAtLeast(0L)
            ),
            text = AppleHtmlText.clean(line.htmlLineText),
            words = words,
            secondary = usableTranslation(line)
        )
    }

    /**
     * Apple times Latin syllables as adjacent non-whitespace words. Dropping
     * the whitespace tokens without merging those syllables makes Bridge
     * insert a display space ("Galway" → "Gal way").
     */
    private fun karaokeWords(words: List<AppleLyricWordModel>): List<LyricWord> {
        val spans = mutableListOf<LatinSyllableSpanMerger.Span>()
        words.forEach { word ->
            if (word.whitespace) {
                markTrailingSpace(spans)
                return@forEach
            }
            val raw = word.text.orEmpty()
            if (raw.firstOrNull()?.isWhitespace() == true) {
                markTrailingSpace(spans)
            }
            val text = AppleHtmlText.clean(raw)
            if (text.isBlank()) return@forEach
            spans += LatinSyllableSpanMerger.Span(
                text = text,
                begin = word.begin.toLong(),
                end = word.end.toLong(),
                hasTrailingSpace = raw.lastOrNull()?.isWhitespace() == true
            )
        }
        return LatinSyllableSpanMerger.merge(spans).map { span ->
            LyricWord(
                begin = span.begin,
                end = span.end,
                duration = (span.end - span.begin).coerceAtLeast(0L),
                text = span.text
            )
        }
    }

    private fun markTrailingSpace(spans: MutableList<LatinSyllableSpanMerger.Span>) {
        if (spans.isEmpty()) return
        val last = spans.last()
        if (!last.hasTrailingSpace) {
            spans[spans.lastIndex] = last.copy(hasTrailingSpace = true)
        }
    }

    /**
     * Pronunciation / romaji never enters the translation lane.
     */
    internal fun usableTranslation(line: AppleLyricLineModel): String? {
        val translation = AppleHtmlText.clean(line.htmlTranslationLineText)
        val lead = AppleHtmlText.clean(line.htmlLineText)
        val pronunciation = AppleHtmlText.clean(line.htmlPronunciationLineText)
        if (translation.isBlank() || translation == "//" || translation == lead) return null
        if (pronunciation.isNotBlank() && translation == pronunciation) return null
        return translation
    }

    private fun isShortBackingVocal(text: String): Boolean {
        val normalized = AppleHtmlText.clean(text).replace(" ", "")
        return normalized.length <= 8 && BACKING_VOCAL_TOKEN.matches(normalized)
    }

    private fun sameNormalizedLyric(left: String, right: String): Boolean {
        val normalizedLeft = AppleHtmlText.normalizeForCompare(left)
        val normalizedRight = AppleHtmlText.normalizeForCompare(right)
        return normalizedLeft.isNotBlank() && normalizedLeft == normalizedRight
    }
}
