/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

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

    private val KUGOU_HASH_SUFFIX_REGEX = Regex("[0-9a-fA-F]{16,}$")
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")

    fun fileIdentityFromPath(path: String): FileIdentity? {
        val fileName = path.substringAfterLast('/').substringBeforeLast('.')
        val stem = fileName.replace(KUGOU_HASH_SUFFIX_REGEX, "").trim().trim('-')
        if (stem.isBlank()) return null

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
            fileArtistKey.contains(artist) ||
            artist.contains(fileArtistKey)

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
