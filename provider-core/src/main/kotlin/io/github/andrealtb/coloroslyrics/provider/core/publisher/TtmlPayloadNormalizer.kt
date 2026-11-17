/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.publisher

/**
 * Prepares a host TTML document for the optional `lyricInfo.ttml` lane.
 *
 * The Bridge reads the document only to learn each line's duet side and background vocals, so
 * a document it could misread is dropped instead of repaired: the timed LRC lanes published
 * next to it already carry the lyric itself.
 */
object TtmlPayloadNormalizer {
    /** Keeps the lane far below the metadata parcel budget shared with the artwork. */
    const val MAX_TTML_CHARS = 200_000

    private val XML_DECLARATION = Regex("""^﻿?\s*<\?xml[^>]*\?>\s*""")

    /** Indentation between tags; it would otherwise be read as lost or doubled word spacing. */
    private val FORMATTING_WHITESPACE = Regex(""">[ \t]*[\r\n]\s*<""")

    fun normalize(ttml: String?): String? {
        if (ttml.isNullOrBlank() || !looksLikeTtml(ttml)) return null
        // The Bridge parser ignores the global offset, so its line times would not match.
        if (ttml.contains("lyricOffset")) return null
        val normalized = ttml
            .replace(XML_DECLARATION, "")
            .replace(FORMATTING_WHITESPACE, "><")
            .trim()
        return normalized.takeIf { it.length <= MAX_TTML_CHARS }
    }

    fun looksLikeTtml(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val probe = value.trimStart('﻿', ' ', '\t', '\r', '\n').take(1000)
        return probe.startsWith("<") &&
            probe.contains("<tt", ignoreCase = true) &&
            probe.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }
}
