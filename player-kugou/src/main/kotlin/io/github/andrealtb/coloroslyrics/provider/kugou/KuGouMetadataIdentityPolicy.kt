/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

object KuGouMetadataIdentityPolicy {

    data class CarLyricDerivedIdentity(
        val realTitle: String,
        val realArtist: String
    )

    private val CAR_LYRIC_DISPLAY_PREFIXES = listOf(
        "演唱", "歌手", "作词", "作曲", "编曲", "词：", "词:", "曲：", "曲:",
        "正在播放", "lyrics by", "composed by", "produced by", "performed by"
    )

    fun looksLikeCarLyricDisplayMetadata(
        title: String?,
        artist: String?,
        currentTitle: String? = null,
        currentArtist: String? = null
    ): Boolean {
        val rawTitle = title.orEmpty().trim()
        if (rawTitle.length < 12) return false
        val hasDisplaySeparator = rawTitle.contains(" - ") ||
            rawTitle.contains(" – ") ||
            rawTitle.contains(" — ") ||
            rawTitle.contains("｜") ||
            rawTitle.contains(" / ")
        if (!hasDisplaySeparator) return false

        val normalizedTitle = normalize(rawTitle)
        val normalizedArtist = normalize(artist)
        if (normalizedArtist.length >= 2 && normalizedTitle.contains(normalizedArtist)) return true

        val expectedTitle = normalize(currentTitle)
        val expectedArtist = normalize(currentArtist)
        return expectedTitle.isNotBlank() &&
            (normalizedTitle.contains(expectedTitle) ||
                normalizedArtist.contains(expectedTitle) ||
                expectedArtist.isNotBlank() && normalizedTitle.contains(expectedArtist))
    }

    /**
     * Recovers the real song identity when KuGou Lite's car-lyric mode churns the
     * MediaSession metadata: the title slot holds a lyric/display line while the
     * artist slot mixes "Artist-Title".
     *
     * A long real title such as "I Knew It, I Knew You" is not a display line.
     * Western "First Last" artist names must not be split on the last space.
     */
    fun carLyricDerivedIdentity(
        title: String?,
        artist: String?
    ): CarLyricDerivedIdentity? {
        val rawTitle = title.orEmpty().trim()
        val normalizedTitle = normalize(rawTitle)
        if (normalizedTitle.isEmpty()) return null

        val rawArtist = artist.orEmpty().trim()
        if (rawArtist.isEmpty()) return null
        if (normalize(rawArtist).contains(normalizedTitle)) return null

        val split = splitCompositeArtist(rawArtist) ?: return null
        val realArtist = split.first.trim()
        val realTitle = split.second.trim()
        if (realArtist.isEmpty() || realTitle.isEmpty()) return null
        if (!looksLikeArtistTitleComposite(realArtist, realTitle)) return null
        if (!looksLikeCarLyricDisplayLine(rawTitle) && !hasArtistTitleSeparator(rawArtist)) {
            return null
        }
        if (normalize(realTitle) == normalizedTitle) return null
        val firstChar = realTitle[0]
        if (firstChar == '(' || firstChar == '（' || firstChar == '[') return null

        return CarLyricDerivedIdentity(realTitle, realArtist)
    }

    private fun looksLikeCarLyricDisplayLine(title: String): Boolean {
        val lowered = title.lowercase()
        if (CAR_LYRIC_DISPLAY_PREFIXES.any { lowered.startsWith(it) }) return true
        val cjkCount = title.count { it.code > 0x7F }
        return cjkCount >= 8 && title.length >= 8
    }

    private fun hasArtistTitleSeparator(artist: String): Boolean {
        if (artist.contains(" - ")) return true
        return listOf("-", "～", "—", "–", "·").any { separator ->
            val index = artist.lastIndexOf(separator)
            index > 0 && index + separator.length < artist.length
        }
    }

    private fun looksLikeArtistTitleComposite(realArtist: String, realTitle: String): Boolean {
        if (realArtist.length < 2) return false
        if (realTitle.length >= 8) return true
        return realTitle.any { it == ' ' || it.code > 0x7F }
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
            val left = artist.substring(0, space).trim()
            val right = artist.substring(space + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty() && containsNonAscii(left, right)) {
                return left to right
            }
        }
        return null
    }

    private fun containsNonAscii(vararg values: String): Boolean =
        values.any { value -> value.any { it.code > 0x7F } }

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
}
