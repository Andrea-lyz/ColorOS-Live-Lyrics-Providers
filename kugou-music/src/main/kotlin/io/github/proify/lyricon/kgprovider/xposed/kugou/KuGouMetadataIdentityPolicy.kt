/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import java.nio.charset.Charset

internal object KuGouMetadataIdentityPolicy {

    private val GB18030: Charset = Charset.forName("GB18030")
    private val CREDIT_TOKEN_REGEX = Regex(
        "(?:^|\\s)(?:\\u8bcd|\\u66f2)\\s*[:\\uff1a]|" +
            "lyricist|composer|arranger|producer|produced\\s+by|vocal|harmony|backing vocal|" +
            "background vocal|recording|mixing|mastering|publisher|copyright|engineer|" +
            "studio|music copyist|conductor|orchestra|choir|piccolo|flute|harmonica|harp|" +
            "\\u4f5c\\u8bcd|\\u4f5c\\u66f2|\\u7f16\\u66f2|\\u5236\\u4f5c\\u4eba|" +
            "\\u6f14\\u5531|\\u4eba\\u58f0|\\u548c\\u58f0|\\u5f55\\u97f3|" +
            "\\u5f55\\u97f3\\u5e08|\\u6df7\\u97f3|\\u6bcd\\u5e26|\\u76d1\\u5236|" +
            "\\u51fa\\u54c1|\\u6307\\u6325|\\u4e50\\u961f|\\u7edf\\u7b79|\\u5f26\\u4e50|\\u5409\\u4ed6|" +
            "\\u8d1d\\u65af|\\u9f13|\\u952e\\u76d8",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeCarLyricDisplayMetadata(
        meta: MetadataData,
        current: MetadataData?
    ): Boolean {
        val rawTitle = meta.title.trim()
        if (rawTitle.length < 12) return false
        val hasDisplaySeparator = rawTitle.contains(" - ") ||
            rawTitle.contains(" – ") ||
            rawTitle.contains(" — ") ||
            rawTitle.contains("｜") ||
            rawTitle.contains(" / ")
        if (!hasDisplaySeparator) return false

        val title = normalize(rawTitle)
        val artist = normalize(meta.artist)
        if (artist.length >= 2 && title.contains(artist)) return true

        val currentTitle = normalize(current?.title)
        val currentArtist = normalize(current?.artist)
        return currentTitle.isNotBlank() &&
            (title.contains(currentTitle) ||
                artist.contains(currentTitle) ||
                currentArtist.isNotBlank() && title.contains(currentArtist))
    }

    fun looksLikeCreditMetadataLine(value: String): Boolean {
        if (value.isBlank()) return false
        if (CREDIT_TOKEN_REGEX.containsMatchIn(value)) return true

        val repaired = repairUtf8DecodedAsGb18030(value)
        return repaired != value && CREDIT_TOKEN_REGEX.containsMatchIn(repaired)
    }

    internal fun repairUtf8DecodedAsGb18030(value: String): String {
        return runCatching {
            String(value.toByteArray(GB18030), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val builder = StringBuilder(value.length)
        value.trim().lowercase().forEach { ch ->
            if (ch.isLetterOrDigit() || ch.code > 0x7F) {
                builder.append(ch)
            }
        }
        return builder.toString()
    }
}
