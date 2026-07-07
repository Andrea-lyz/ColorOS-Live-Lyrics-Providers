/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import java.util.Locale

class MetadataData(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val mediaId: String,
    val mediaUri: String
) {
    val trackKey by lazy {
        buildTrackKey(title, artist)
    }

    val generateId by lazy {
        listOf(title, artist, album)
            .joinToString("-")
            .hashCode()
            .toString()
    }

    private val legacyGenerateId by lazy {
        "$title-$artist-$album-$duration".hashCode().toString()
    }

    val identityId by lazy {
        mediaId.ifBlank {
            trackKey.ifBlank { generateId }
        }
    }

    val identityKeys by lazy {
        linkedSetOf(
            identityId,
            mediaId,
            mediaUri,
            trackKey,
            generateId,
            legacyGenerateId
        ).filter { it.isNotBlank() }.toSet()
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        private fun buildTrackKey(title: String?, artist: String?): String {
            val normalizedTitle = normalizeTrackComponent(title)
            if (normalizedTitle.isEmpty()) return ""
            return normalizedTitle + "|" + normalizeTrackComponent(artist)
        }

        private fun normalizeTrackComponent(value: String?): String {
            return value.orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
                .replace(WHITESPACE_REGEX, " ")
        }
    }
}
