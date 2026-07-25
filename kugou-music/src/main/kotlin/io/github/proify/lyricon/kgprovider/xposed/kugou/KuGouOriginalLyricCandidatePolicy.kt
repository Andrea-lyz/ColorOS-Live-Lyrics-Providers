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

    private fun firstNonBlank(value: String?): String = value.orEmpty().trim()

    private fun normalize(value: String): String {
        return buildString(value.length) {
            value.lowercase().forEach { character ->
                if (character.isLetterOrDigit() || character.code > 0x7F) {
                    append(character)
                }
            }
        }
    }
}
