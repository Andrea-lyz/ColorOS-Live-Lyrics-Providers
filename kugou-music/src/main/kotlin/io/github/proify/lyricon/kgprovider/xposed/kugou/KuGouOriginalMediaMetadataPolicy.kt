/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

/**
 * Separates KuGou's transient car-lyric MediaSession metadata from a real track change.
 *
 * Album, artist and duration are intentionally not used as a standalone identity here:
 * alternate-language releases can share all three while still being different tracks.
 */
internal object KuGouOriginalMediaMetadataPolicy {

    fun shouldSuppressCarLyricMetadata(
        current: MetadataData,
        incoming: MetadataData,
        currentLyricTexts: Iterable<String>
    ): Boolean {
        val currentTitle = normalize(current.title)
        val incomingTitle = normalize(incoming.title)
        if (currentTitle.isEmpty() || incomingTitle.isEmpty() || currentTitle == incomingTitle) {
            return false
        }

        if (currentLyricTexts.any { normalize(it) == incomingTitle }) {
            return true
        }
        if (KuGouMetadataIdentityPolicy.looksLikeCarLyricDisplayMetadata(incoming, current)) {
            return true
        }
        if (hasSameExplicitMediaIdentity(current, incoming)) {
            return true
        }
        return looksLikeSameTrackMetadataChurn(current, incoming, currentTitle, incomingTitle)
    }

    private fun hasSameExplicitMediaIdentity(
        current: MetadataData,
        incoming: MetadataData
    ): Boolean {
        return current.mediaId.isNotBlank() && current.mediaId == incoming.mediaId ||
            current.mediaUri.isNotBlank() && current.mediaUri == incoming.mediaUri
    }

    private fun looksLikeSameTrackMetadataChurn(
        current: MetadataData,
        incoming: MetadataData,
        currentTitle: String,
        incomingTitle: String
    ): Boolean {
        if (current.duration <= 0L || current.duration != incoming.duration) return false
        val currentArtist = normalize(current.artist)
        val incomingArtist = normalize(incoming.artist)
        val titleDecorated = incomingTitle.contains(currentTitle) &&
            (currentArtist.isEmpty() ||
                incomingTitle.contains(currentArtist) ||
                incomingArtist.contains(currentArtist))
        val artistMergedWithTitle = currentArtist.isNotEmpty() &&
            incomingArtist.contains(currentArtist) &&
            incomingArtist.contains(currentTitle)
        return titleDecorated || artistMergedWithTitle
    }

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return buildString(value.length) {
            value.trim().lowercase().forEach { character ->
                if (character.isLetterOrDigit() || character.code > 0x7F) {
                    append(character)
                }
            }
        }
    }
}
