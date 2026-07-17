/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

internal object KuGouMetadataIdentityPolicy {

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
            if (ch.isLetterOrDigit() || ch.code > 0x7F) {
                builder.append(ch)
            }
        }
        return builder.toString()
    }
}
