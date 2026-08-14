/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

internal object KuGouMetadataIdentityPolicy {

    data class CarLyricDerivedIdentity(
        val realTitle: String,
        val realArtist: String
    )

    private val CAR_LYRIC_DISPLAY_PREFIXES = listOf(
        "演唱", "歌手", "作词", "作曲", "编曲", "词：", "词:", "曲：", "曲:",
        "正在播放", "lyrics by", "composed by", "produced by", "performed by"
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

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val builder = StringBuilder(value.length)
        value.trim().lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Recovers the real song identity when KuGou Lite's car-lyric mode churns the
     * MediaSession metadata: the title slot holds a lyric/display line ("演唱: X",
     * a long lyric line, ...) while the artist slot mixes "Artist-Title"
     * ("对角音乐 逐梦滚烫", "Troye Sivan-She's the Best (Explicit)").
     *
     * Returns the derived (realTitle, realArtist) only for clearly churned shapes;
     * returns null for stable metadata (title embedded in the artist slot, short
     * latin titles, single-segment artists) so callers keep the metadata untouched.
     */
    fun carLyricDerivedIdentity(
        title: String?,
        artist: String?
    ): CarLyricDerivedIdentity? {
        val rawTitle = title.orEmpty().trim()
        val normalizedTitle = normalize(rawTitle)
        if (normalizedTitle.isEmpty() || !looksLikeCarLyricDisplayLine(rawTitle)) return null

        val rawArtist = artist.orEmpty().trim()
        if (rawArtist.isEmpty()) return null
        if (normalize(rawArtist).contains(normalizedTitle)) return null

        val split = splitCompositeArtist(rawArtist) ?: return null
        val realArtist = split.first.trim()
        val realTitle = split.second.trim()
        if (realArtist.isEmpty() || realTitle.isEmpty()) return null
        if (normalize(realTitle) == normalizedTitle) return null
        val firstChar = realTitle[0]
        if (firstChar == '(' || firstChar == '（' || firstChar == '[') return null

        return CarLyricDerivedIdentity(realTitle, realArtist)
    }

    private fun looksLikeCarLyricDisplayLine(title: String): Boolean {
        if (title.length >= 20) return true
        val lowered = title.lowercase()
        if (CAR_LYRIC_DISPLAY_PREFIXES.any { lowered.startsWith(it) }) return true
        val cjkCount = title.count { it.code > 0x7F }
        return cjkCount >= 8 && title.length >= 8
    }

    private fun splitCompositeArtist(artist: String): Pair<String, String>? {
        val spacedDash = artist.lastIndexOf(" - ")
        if (spacedDash > 0 && spacedDash + 3 < artist.length) {
            return artist.substring(0, spacedDash) to artist.substring(spacedDash + 3)
        }

        var bestIndex = -1
        var bestLength = 0
        for (separator in listOf("-", "～", "—", "–", "·")) {
            val index = artist.lastIndexOf(separator)
            if (index > 0) {
                val end = index + separator.length
                if (end > bestIndex + bestLength) {
                    bestIndex = index
                    bestLength = separator.length
                }
            }
        }
        if (bestIndex > 0 && bestIndex + bestLength < artist.length) {
            return artist.substring(0, bestIndex) to artist.substring(bestIndex + bestLength)
        }

        val space = artist.lastIndexOf(' ')
        if (space > 0 && space + 1 < artist.length) {
            return artist.substring(0, space) to artist.substring(space + 1)
        }
        return null
    }
}
