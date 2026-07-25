/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlin.math.max

/**
 * Repairs negative or relative word-time offsets so Providers can emit a flat
 * absolute timestamp that the Bridge renderer can sort directly.
 *
 * <p>Some Providers ship {@code wordTime} as either a negative pre-roll offset
 * (Honor / NetEase Cloud Music) or a relative offset measured from the start of
 * the line (older KuGou edges). The renderer assumes monotonic non-negative
 * timestamps, so this helper:
 * <ul>
 *   <li>Clamps negative inputs to {@code 0}.</li>
 *   <li>Detects a clearly relative offset by checking that {@code wordTime} is
 *       at least the lead-in budget before {@code line.begin} and within an
 *       extra absolute budget tied to the line duration.</li>
 *   <li>Returns {@code line.begin + wordTime} when both signals line up;
 *       otherwise returns the input untouched.</li>
 * </ul>
 *
 * <p>The thresholds are exposed via {@link #MAX_LEAD_IN_MS} and
 * {@link #MAX_RELATIVE_OFFSET_MS} for documentation and tests; QQ QRC keeps
 * its own dedicated {@code QQMusicQrcWordTimePolicy} and is intentionally not
 * routed through this normalizer.
 */
object WordTimeNormalizer {
    /**
     * Maximum amount (ms) by which a relative {@code wordTime} may precede
     * {@code line.begin} before we treat it as relative rather than absolute.
     * Mirrors the historical "250ms lead-in" used by Spotify/Apple/NetEase/KuGou.
     */
    const val MAX_LEAD_IN_MS: Long = 250L

    /**
     * Minimum amount (ms) of headroom above the declared line span that a
     * relative {@code wordTime} may reach while still being trusted as relative.
     * Mirrors the historical "2000ms" ceiling; the actual limit is the larger of
     * {@code lineDuration + MAX_RELATIVE_OFFSET_MS} and {@code MAX_RELATIVE_OFFSET_MS}.
     */
    const val MAX_RELATIVE_OFFSET_MS: Long = 2_000L

    /**
     * Returns an absolute word time suitable for downstream sorting. Negative
     * inputs are clamped; relative offsets that match the lead-in/span pattern
     * are rewritten as {@code line.begin + wordTime}; otherwise the input is
     * returned unchanged.
     */
    fun toAbsolute(line: RichLyricLine, wordTime: Long): Long {
        if (wordTime < 0L) return 0L
        val lineDuration = max(line.duration, line.end - line.begin)
        if (line.begin > 0L &&
            wordTime + MAX_LEAD_IN_MS < line.begin &&
            wordTime <= max(lineDuration + MAX_RELATIVE_OFFSET_MS, MAX_RELATIVE_OFFSET_MS)
        ) {
            return line.begin + wordTime
        }
        return wordTime
    }
}
