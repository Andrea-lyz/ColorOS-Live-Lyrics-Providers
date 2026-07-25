/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

/**
 * Builds the stable per-track identity used by Providers and accepted on the
 * Bridge side as the canonical {@code trackKey} extra.
 *
 * <p>Providers historically inlined {@code buildTrackKey(title, artist)} with a
 * single {@code normalizeTrackComponent} that lower-cases, collapses whitespace,
 * and folds Unicode apostrophes to {@code '}. This object centralizes both the
 * key shape and the component normalization; behavior matches the historical
 * Spotify/Apple/QQ/Netease/KuGou/Qishui inlined implementations bit-for-bit so
 * existing Bridge matching keys remain stable.
 */
object TrackKeyBuilder {
    /**
     * Returns {@code "title|artist"} after normalizing both sides, or an empty
     * string when the title is blank after normalization. An empty result means
     * the Provider declines to identify the track via the title-based fallback.
     */
    fun build(title: String?, artist: String?): String {
        val normalizedTitle = normalizeTrackComponent(title)
        if (normalizedTitle.isBlank()) return ""
        return normalizedTitle + "|" + normalizeTrackComponent(artist)
    }

    /**
     * Lower-cases, trims, and collapses whitespace runs to a single space while
     * also folding the common Unicode apostrophe variants to {@code '}.
     */
    fun normalizeTrackComponent(value: String?): String {
        if (value == null) return ""
        val builder = StringBuilder(value.length)
        var inWhitespace = false
        value.trim().forEach { raw ->
            val ch = when (raw) {
                '\u2018', '\u2019', '\u02BC', '\uFF07' -> '\''
                else -> raw.lowercaseChar()
            }
            val whitespace = ch == ' ' || ch == '\t'
            if (whitespace) {
                if (!inWhitespace) builder.append(' ')
            } else {
                builder.append(ch)
            }
            inWhitespace = whitespace
        }
        return builder.toString()
    }
}
