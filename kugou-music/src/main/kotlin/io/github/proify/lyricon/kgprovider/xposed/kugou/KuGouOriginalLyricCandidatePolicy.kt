/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

/**
 * Guards the original KuGou lyric callback against a delayed result belonging to another song.
 *
 * The upstream callback only exposes a lyric file path and a [RichLyricLine]-style result; it
 * does not carry the song identity that requested it.  KuGou commonly places a timed
 * "title - artist" line first, which lets us reject only an explicitly foreign result instead
 * of assuming that every late callback is stale.
 */
internal object KuGouOriginalLyricCandidatePolicy {

    /**
     * Caller-side wrapper that lets us bypass the strict contains check when
     * the lyric callback was captured under the same song identity that is
     * now current.  Without this shortcut, KRC files whose leading line mixes
     * Hangul and Romanised title (e.g. "BANG BANG BANG (뱅뱅행행) - BIGBANG")
     * are rejected as foreign even when the MediaMetadata only exposes one
     * of the two scripts and the candidate clearly belongs to the current
     * track.  Keeping the captured-id comparison outside the policy file
     * also lets the policy remain a pure text matcher.
     */
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

    /**
     * Parses the song identity out of a KuGou lyric file name.
     *
     * KuGou names downloaded lyric files "Artist - Title-<hash>.krc" (CJK names
     * sometimes omit the spaces: "Artist-Title-<hash>.krc").  Returns null when
     * the name does not carry a separable artist/title pair, so callers keep the
     * legacy behavior for unparseable files.
     */
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

    /**
     * Rejects a candidate whose KRC file belongs to another song.
     *
     * The file stem carries the song identity independently of the captured track
     * snapshot.  A candidate is foreign only when it gives a decisive mismatch:
     * the file title matches neither the expected title nor the expected artist
     * (car-lyric churn mixes "Artist-Title" into the artist slot), and the titles
     * share no significant token (script-mixed titles like
     * "BANG BANG BANG (뱅뱅행행)" fail plain containment but share words).  An
     * artist-only match rescues only when the metadata title is unusable, so a
     * same-artist next track ("Lukas Graham - 7 Years" while "Good Times" is
     * current) is still rejected.
     */
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

        // Car-lyric churn: the artist slot mixes "Artist-Title" and the file title
        // is embedded in it, so the file belongs to the current track.
        if (artist.isNotEmpty() && artist.contains(fileTitleKey)) return false

        // Script-mixed titles fail plain containment; accept on a shared
        // significant token while the artist stays consistent.
        if (title.isNotEmpty() && artistConsistent &&
            sharesSignificantToken(fileTitle, expectedTitle.orEmpty())
        ) {
            return false
        }

        // Artist-only rescue when the metadata title is unusable (blank).
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
