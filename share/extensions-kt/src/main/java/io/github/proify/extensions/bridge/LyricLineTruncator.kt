/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

/**
 * Shrinks a v4 external-lyric payload by dropping middle lines so the broadcast
 * can fit inside {@link io.github.proify.extensions.android.SystemUiBroadcastSender#MAX_PARCEL_BYTES}
 * without losing the song's opening cue or the chorus tail.
 *
 * <p>SystemUI rejects a payload as oversized only when both the original
 * {@code lyric+rawLyric+translationLyric} triple and its line-only downgrade
 * still exceed the budget. In that case the Provider would otherwise lose the
 * entire song. This helper provides a deterministic, line-local reduction so
 * one retry can still publish something the user can read.</p>
 *
 * <p>The truncator is intentionally Android-free: it operates on the raw LRC
 * strings and the byte budget. Callers rebuild the intent after each
 * truncation attempt so the parcel reflects the new strings.</p>
 */
object LyricLineTruncator {

    /**
     * Bytes reserved for the static fields attached to every v4 broadcast
     * (source/player/sender, trackKey/songName/artist/duration, metadata,
     * protocolVersion/eventType/requestId, etc.). 16 KiB comfortably fits the
     * measured payload envelope while leaving room for non-ASCII titles.
     */
    const val METADATA_HEADROOM_BYTES: Int = 16 * 1024

    /**
     * Headroom kept between the post-truncation string bytes and the
     * configured {@link #byteBudget}. Accounted for Intent key/value overhead
     * and the slight Parcel growth that occurs after a second {@code writeToParcel}.
     */
    const val SAFETY_HEADROOM_BYTES: Int = 512

    /**
     * Fraction of lines preserved from the head of the lyric when a
     * truncation is required. Bridges the first 70% of the song so the
     * opening cue survives.
     */
    const val HEAD_KEEP_RATIO: Double = 0.7

    /**
     * Fraction of lines preserved from the tail of the lyric when a
     * truncation is required. Bridges the last 30% of the song so the
     * chorus tail survives.
     */
    const val TAIL_KEEP_RATIO: Double = 0.3

    /**
     * Builds a byte budget for the lyric triple from a hard Parcel cap.
     * The result is non-negative and at most {@code cap}.
     */
    fun byteBudget(parcelCapBytes: Int): Int {
        val safeCap = parcelCapBytes.coerceAtLeast(0)
        return (safeCap - METADATA_HEADROOM_BYTES - SAFETY_HEADROOM_BYTES).coerceAtLeast(0)
    }

    /**
     * Truncates a single lyric block by dropping the middle lines until the
     * string fits inside {@code maxBytes}. If no complete line fits, it keeps a
     * UTF-8-safe prefix of the opening line. The returned text never exceeds
     * {@code maxBytes}.
     *
     * @return the truncated string and how many lines were removed.
     */
    fun truncateByLines(value: String, maxBytes: Int): Truncated {
        val targetBytes = maxBytes.coerceAtLeast(0)
        if (byteCount(value) <= targetBytes) return Truncated(value, removedLines = 0)

        if (value.isEmpty() || targetBytes == 0) {
            return Truncated(
                text = "",
                removedLines = countLines(value),
                contentChanged = value.isNotEmpty()
            )
        }

        val trimmed = value.trimEnd('\n', ' ')
        if (trimmed.isEmpty()) {
            return Truncated(
                text = "",
                removedLines = countLines(value),
                contentChanged = value.isNotEmpty()
            )
        }
        if (byteCount(trimmed) <= targetBytes) {
            return Truncated(
                text = trimmed,
                removedLines = 0,
                contentChanged = trimmed != value
            )
        }

        val lines = trimmed.split('\n')
        val lineBytes = lines.map(::byteCount)
        val headByteTotals = LongArray(lines.size + 1)
        val tailByteTotals = LongArray(lines.size + 1)
        for (index in lines.indices) {
            headByteTotals[index + 1] = headByteTotals[index] + lineBytes[index]
        }
        for (tailSize in 1..lines.size) {
            tailByteTotals[tailSize] = tailByteTotals[tailSize - 1] + lineBytes[lines.size - tailSize]
        }

        var headKeep = ((lines.size) * HEAD_KEEP_RATIO).toInt().coerceIn(1, lines.size)
        val tailCount = ((lines.size) * TAIL_KEEP_RATIO).toInt().coerceAtLeast(0)
        var tailKeep = computeTailKeep(lines.size, headKeep, tailCount)

        // Keep the opening cue and the requested tail allocation. Reduce the
        // non-leading head lines first, then tail lines only if necessary. The
        // prefix/suffix byte totals keep this search linear for long lyrics.
        while (true) {
            val keptCount = headKeep + tailKeep
            val keptBytes = headByteTotals[headKeep] + tailByteTotals[tailKeep] +
                (keptCount - 1).coerceAtLeast(0)
            if (keptBytes <= targetBytes.toLong()) {
                val keptLines = buildList(keptCount) {
                    addAll(lines.subList(0, headKeep))
                    if (tailKeep > 0) addAll(lines.subList(lines.size - tailKeep, lines.size))
                }
                val keptText = keptLines.joinToString(separator = "\n")
                val removed = (lines.size - keptCount).coerceAtLeast(0)
                return Truncated(
                    text = keptText,
                    removedLines = removed,
                    contentChanged = keptText != value
                )
            }
            if (headKeep > 1) {
                headKeep--
                continue
            }
            if (tailKeep > 0) {
                tailKeep--
                continue
            }
            break
        }

        val firstLine = truncateUtf8(lines.first(), targetBytes)
        return Truncated(
            text = firstLine,
            removedLines = (lines.size - 1).coerceAtLeast(0),
            contentChanged = firstLine != value
        )
    }

    private fun computeTailKeep(total: Int, headKeep: Int, requestedTail: Int): Int {
        if (requestedTail <= 0) return 0
        val maxTail = (total - headKeep).coerceAtLeast(0)
        return requestedTail.coerceAtMost(maxTail)
    }

    /**
     * Truncates the three lyric extras of a v4 payload to fit inside
     * {@code maxBytes}. Budget is allocated from highest to lowest rendering
     * priority: {@code rawLyric}, then {@code lyric}, then
     * {@code translationLyric}. This is equivalent to shrinking translation
     * first while preserving Bridge word timing whenever possible.
     */
    fun truncatePayload(
        lyric: String,
        rawLyric: String,
        translationLyric: String,
        maxBytes: Int
    ): TruncatedPayload {
        var remainingBytes = maxBytes.coerceAtLeast(0)
        val rawReduced = truncateByLines(rawLyric, remainingBytes)
        remainingBytes = (remainingBytes - byteCount(rawReduced.text)).coerceAtLeast(0)

        val lyricReduced = truncateByLines(lyric, remainingBytes)
        remainingBytes = (remainingBytes - byteCount(lyricReduced.text)).coerceAtLeast(0)

        val translation = truncateByLines(translationLyric, remainingBytes)
        return TruncatedPayload(
            lyric = lyricReduced,
            rawLyric = rawReduced,
            translationLyric = translation
        )
    }

    private fun countLines(value: String): Int {
        val withoutTrailingNewlines = value.trimEnd('\n')
        if (withoutTrailingNewlines.isEmpty()) return 0
        return withoutTrailingNewlines.count { it == '\n' } + 1
    }

    private fun byteCount(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0 || value.isEmpty()) return ""
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val token = String(Character.toChars(codePoint))
            val tokenBytes = byteCount(token)
            if (bytes + tokenBytes > maxBytes) break
            result.append(token)
            bytes += tokenBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    data class Truncated(
        val text: String,
        val removedLines: Int,
        private val contentChanged: Boolean = removedLines > 0
    ) {
        val changed: Boolean get() = contentChanged || removedLines > 0
    }

    data class TruncatedPayload(
        val lyric: Truncated,
        val rawLyric: Truncated,
        val translationLyric: Truncated
    ) {
        val changed: Boolean
            get() = lyric.changed || rawLyric.changed || translationLyric.changed
    }
}
