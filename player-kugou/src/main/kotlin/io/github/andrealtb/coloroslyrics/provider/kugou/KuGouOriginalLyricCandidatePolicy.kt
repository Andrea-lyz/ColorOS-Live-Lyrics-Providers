/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import kotlin.math.abs

/**
 * Guards a KuGou lyric callback against a delayed result belonging to another song.
 *
 * The upstream callback only exposes a lyric file path and a lyric-data result; it
 * does not carry the song identity that requested it. KuGou commonly places a timed
 * "title - artist" line first, which lets us reject only an explicitly foreign result.
 */
object KuGouOriginalLyricCandidatePolicy {

    fun hasForeignLeadingMetadata(
        capturedSongId: String?,
        currentSongId: String?,
        firstLineText: String?,
        expectedTitle: String?,
        expectedArtist: String?
    ): Boolean {
        val captured = capturedSongId.orEmpty()
        val current = currentSongId.orEmpty()
        if (captured.isNotEmpty() && current.isNotEmpty() && captured == current) {
            return false
        }
        return hasForeignLeadingMetadata(firstLineText, expectedTitle, expectedArtist)
    }

    fun hasForeignLeadingMetadata(
        firstLineText: String?,
        expectedTitle: String?,
        expectedArtist: String?
    ): Boolean {
        val line = firstLineText.orEmpty().trim()
        val expectedTitleKey = normalize(firstNonBlank(expectedTitle))
        val artist = firstNonBlank(expectedArtist)
        if (line.isEmpty() || expectedTitleKey.isEmpty() || artist.isEmpty()) {
            return false
        }

        val artistIndex = line.indexOf(artist, ignoreCase = true)
        if (artistIndex <= 0) return false

        val beforeArtist = line.substring(0, artistIndex).trimEnd()
        val separatorIndex = beforeArtist.indexOfLast { it == '-' || it == '/' || it == '|' }
        if (separatorIndex <= 0 || beforeArtist.substring(separatorIndex + 1).isNotBlank()) {
            return false
        }

        val candidateTitleKey = normalize(beforeArtist.substring(0, separatorIndex))
        return candidateTitleKey.isNotEmpty() && !candidateTitleKey.contains(expectedTitleKey)
    }

    data class FileIdentity(
        val artist: String,
        val title: String
    )

    data class TimedText(val timeMs: Long, val key: String)

    private val KUGOU_HASH_SUFFIX_REGEX = Regex("[0-9a-fA-F]{16,}$")
    private val LRC_TIME_REGEX = Regex("""^\[([0-9]{1,3}):([0-9]{2})(?:[.:]([0-9]{1,3}))?]""")
    private const val MIN_MATCHED_LINES = 3
    private const val LINE_TIME_TOLERANCE_MS = 1_000L
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
    private val ARTIST_SEPARATOR_REGEX = Regex(
        "\\s*(?:[\u3001,\uFF0C/\uFF0F&\uFF06;\uFF1B|+\u00D7]|\\bfeat\\.?|\\bft\\.|\\bwith\\b|\\bx\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    private const val MIN_CONTAINED_TITLE = 4

    fun fileIdentityFromPath(path: String): FileIdentity? {
        val stem = fileStem(path) ?: return null

        val spacedSeparator = stem.indexOf(" - ")
        val separator = if (spacedSeparator > 0) {
            spacedSeparator
        } else {
            val plain = stem.lastIndexOf('-')
            if (plain <= 0) return null else plain
        }
        if (separator >= stem.length - 1) return null

        val artist = stem.substring(0, separator).trim()
        val title = stem.substring(separator + if (spacedSeparator > 0) 3 else 1).trim()
        if (artist.isBlank() || title.isBlank()) return null
        return FileIdentity(artist, title)
    }

    fun isForeignFileIdentity(
        fileArtist: String,
        fileTitle: String,
        expectedTitle: String?,
        expectedArtist: String?
    ): Boolean {
        val title = normalize(expectedTitle.orEmpty())
        val artist = normalize(expectedArtist.orEmpty())
        if (title.isEmpty() && artist.isEmpty()) return false

        val fileTitleKey = normalize(fileTitle)
        if (fileTitleKey.isEmpty()) return false
        val fileArtistKey = normalize(fileArtist)

        val artistConsistent = artist.isEmpty() ||
            fileArtistKey.isEmpty() ||
            artistsOverlap(fileArtist, expectedArtist.orEmpty())

        val titleContained = title.isNotEmpty() &&
            (fileTitleKey.contains(title) || title.contains(fileTitleKey))
        if (titleContained && artistConsistent) return false

        if (artist.isNotEmpty() && artist.contains(fileTitleKey)) return false

        if (title.isNotEmpty() && artistConsistent &&
            sharesSignificantToken(fileTitle, expectedTitle.orEmpty())
        ) {
            return false
        }

        if (title.isEmpty() && artist.isNotEmpty() && fileArtistKey.isNotEmpty() &&
            (fileArtistKey.contains(artist) || artist.contains(fileArtistKey))
        ) {
            return false
        }

        return true
    }

    /**
     * Positive evidence that a lyric callback belongs to the expected song, independent of the
     * generation that captured it: the KuGou file name ("artist - title-hash" or "title-hash")
     * or a leading "title - artist" line names that song. Returns the evidence kind
     * ("file", "file-title" or "lead"), or null when nothing names it.
     */
    fun positiveMatch(
        path: String,
        firstLineText: String?,
        expectedTitle: String?,
        expectedArtist: String?
    ): String? {
        val title = normalize(expectedTitle.orEmpty())
        if (title.isEmpty()) return null
        val identity = fileIdentityFromPath(path)
        if (identity != null) {
            if (titlesMatch(normalize(identity.title), title) &&
                artistsOverlap(identity.artist, expectedArtist.orEmpty())
            ) {
                return "file"
            }
        } else if (fileStem(path)?.let(::normalize) == title) {
            return "file-title"
        }
        return if (hasMatchingLeadingMetadata(firstLineText, title, expectedArtist)) "lead" else null
    }

    private fun hasMatchingLeadingMetadata(
        firstLineText: String?,
        titleKey: String,
        expectedArtist: String?
    ): Boolean {
        val line = firstLineText.orEmpty()
        if (line.none { it == '-' || it == '/' || it == '\uFF0F' || it == '|' }) return false
        val lineKey = normalize(line)
        if (!lineKey.contains(titleKey)) return false
        return artistElements(expectedArtist.orEmpty()).any { it.length >= 2 && lineKey.contains(it) }
    }

    private fun titlesMatch(fileTitleKey: String, titleKey: String): Boolean {
        if (fileTitleKey.isEmpty() || titleKey.isEmpty()) return false
        if (fileTitleKey == titleKey) return true
        if (minOf(fileTitleKey.length, titleKey.length) < MIN_CONTAINED_TITLE) return false
        return fileTitleKey.contains(titleKey) || titleKey.contains(fileTitleKey)
    }

    /**
     * Artist fields name the same performers when one contains the other or, for multi-artist
     * credits written in a different order or with different separators
     * ("HOYO-MiX、Artist" vs "Artist/HOYO-MiX"), when they share one performer.
     */
    private fun artistsOverlap(first: String, second: String): Boolean {
        val firstKey = normalize(first)
        val secondKey = normalize(second)
        if (firstKey.isEmpty() || secondKey.isEmpty()) return false
        if (firstKey.contains(secondKey) || secondKey.contains(firstKey)) return true
        val secondElements = artistElements(second)
        return artistElements(first).any { it in secondElements }
    }

    private fun artistElements(value: String): Set<String> =
        value.split(ARTIST_SEPARATOR_REGEX)
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .toSet()

    private fun fileStem(path: String): String? {
        val fileName = path.substringAfterLast('/').substringBeforeLast('.')
        return fileName.replace(KUGOU_HASH_SUFFIX_REGEX, "").trim().trim('-').takeIf { it.isNotBlank() }
    }

    /** Timed, non-empty lines of an LRC text such as the `lyric` field of KuGou's lyricInfo. */
    fun parseTimedLyric(lrc: String): List<TimedText> {
        val result = ArrayList<TimedText>()
        lrc.lineSequence().forEach { raw ->
            var rest = raw.trim()
            val times = ArrayList<Long>(1)
            while (true) {
                val match = LRC_TIME_REGEX.find(rest) ?: break
                times += lrcTimeMillis(match) ?: break
                rest = rest.substring(match.range.last + 1)
            }
            val key = normalize(rest)
            if (key.isNotEmpty()) times.forEach { result += TimedText(it, key) }
        }
        return result
    }

    /**
     * Whether [lines] carry the same text at the same times as [official]: at least
     * [MIN_MATCHED_LINES] lines and half of the shorter lyric must match within
     * [LINE_TIME_TOLERANCE_MS]. Content, unlike a file name, cannot be renamed or translated.
     */
    fun matchesTimedLyric(official: List<TimedText>, lines: List<RichLyricLine>): Boolean {
        if (official.size < MIN_MATCHED_LINES) return false
        val candidate = lines.mapNotNull { line ->
            normalize(line.text.orEmpty()).takeIf { it.isNotEmpty() }?.let { TimedText(line.begin, it) }
        }
        if (candidate.size < MIN_MATCHED_LINES) return false
        val officialByKey = official.groupBy { it.key }
        val matched = candidate.count { line ->
            officialByKey[line.key]?.any { abs(it.timeMs - line.timeMs) <= LINE_TIME_TOLERANCE_MS } == true
        }
        return matched >= MIN_MATCHED_LINES && matched * 2 >= minOf(official.size, candidate.size)
    }

    private fun lrcTimeMillis(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun sharesSignificantToken(fileTitle: String, expectedTitle: String): Boolean {
        val fileTokens = significantTokens(fileTitle)
        if (fileTokens.isEmpty()) return false
        return significantTokens(expectedTitle).any { fileTokens.contains(it) }
    }

    private fun significantTokens(value: String): Set<String> {
        return TOKEN_REGEX.findAll(value.lowercase())
            .map { it.value }
            .filter { it.length >= 4 }
            .toSet()
    }

    private fun firstNonBlank(value: String?): String = value.orEmpty().trim()

    private fun normalize(value: String): String {
        return buildString(value.length) {
            value.lowercase().forEach { character ->
                if (character.isLetterOrDigit()) {
                    append(character)
                }
            }
        }
    }
}
