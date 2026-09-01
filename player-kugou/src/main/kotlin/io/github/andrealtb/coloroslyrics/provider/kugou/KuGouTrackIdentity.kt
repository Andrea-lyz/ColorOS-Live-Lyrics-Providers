/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import java.util.Locale

object KuGouTrackIdentity {

    private val WHITESPACE_REGEX = Regex("\\s+")

    fun trackKey(title: String?, artist: String?): String {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return ""
        return normalizedTitle + "|" + normalize(artist)
    }

    fun identityKeys(track: TrackIdentity, extra: Iterable<String> = emptyList()): Set<String> {
        return linkedSetOf(
            track.id.orEmpty(),
            trackKey(track.title, track.artist),
            track.title.orEmpty(),
            *extra.map { it.trim() }.toTypedArray()
        ).filter { it.isNotBlank() }.toSet()
    }

    /**
     * KuGou fills the same metadata transaction in stages: title/artist first, then mediaId,
     * and finally the official lyricInfo songId. Those IDs use different namespaces and must
     * remain publication aliases rather than generation boundaries.
     */
    fun generationIdentity(track: TrackIdentity): TrackIdentity = track.copy(id = null)

    fun sanitize(
        hostPackage: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        mediaId: String?,
        songIdFromLyricInfo: String?
    ): TrackIdentity {
        val rawTitle = title.orEmpty().trim()
        val rawArtist = artist.orEmpty().trim()
        val derived = if (KuGouPlayerConstants.isLite(hostPackage)) {
            KuGouMetadataIdentityPolicy.carLyricDerivedIdentity(rawTitle, rawArtist)
        } else {
            null
        }
        val stableTitle = derived?.realTitle ?: rawTitle
        val stableArtist = derived?.realArtist ?: rawArtist
        val id = firstNonBlank(
            songIdFromLyricInfo,
            mediaId,
            trackKey(stableTitle, stableArtist)
        )
        return TrackIdentity(
            id = id,
            title = stableTitle,
            artist = stableArtist,
            album = album.orEmpty(),
            durationMs = durationMs
        )
    }

    fun looksLikeMetadataLead(lineText: String?, title: String?, artist: String?): Boolean {
        if (lineText.isNullOrBlank()) return false
        val text = normalizeLetters(lineText)
        val expectedTitle = normalizeLetters(title)
        val expectedArtist = normalizeLetters(artist)
        if (text.isBlank() || expectedTitle.isBlank()) return false
        if (expectedArtist.isNotBlank() && text.contains(expectedTitle) && text.contains(expectedArtist)) {
            return lineText.any { it == '-' || it == '/' || it == '／' || it == '|' }
        }
        if (expectedTitle.length < 2 || !text.contains(expectedTitle) || text == expectedTitle) {
            return false
        }
        val hasSeparator = lineText.any { it == '-' || it == '/' || it == '／' || it == '|' }
        return hasSeparator || text.startsWith(expectedTitle) && text.length > expectedTitle.length + 1
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun normalize(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
    }

    private fun normalizeLetters(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return buildString(value.length) {
            value.lowercase().forEach { character ->
                if (character.isLetterOrDigit()) append(character)
            }
        }
    }
}
