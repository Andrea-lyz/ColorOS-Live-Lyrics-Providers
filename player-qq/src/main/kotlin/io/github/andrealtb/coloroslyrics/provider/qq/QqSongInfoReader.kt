/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

object QqSongInfoReader {
    private val SONG_ID_METHODS = listOf("H2", "J2", "getSongId", "v2", "getId")
    private val TITLE_METHODS = listOf("j3", "getTitle", "X2")
    private val ARTIST_METHODS = listOf("V3", "getArtist", "C3")

    fun read(songInfo: Any?): TrackIdentity {
        if (songInfo == null) return TrackIdentity()
        val songId = firstNonPlaceholder(
            SONG_ID_METHODS.map { stringify(QqLyricModelDecoder.invokeNoArg(songInfo, it)) }
        )
        val title = firstNonPlaceholder(
            TITLE_METHODS.map { stringify(QqLyricModelDecoder.invokeNoArg(songInfo, it)) }
        )
        val artist = firstNonPlaceholder(
            ARTIST_METHODS.map { stringify(QqLyricModelDecoder.invokeNoArg(songInfo, it)) }
        )
        return TrackIdentity(
            id = songId,
            title = title,
            artist = artist
        )
    }

    fun readFromLoadBean(bean: Any?): Pair<TrackIdentity, LyricModels> {
        val songInfo = firstNonNull(
            QqLyricModelDecoder.invokeNoArg(bean, "f"),
            QqLyricModelDecoder.readField(bean, "a")
        )
        val primary = firstNonNull(
            QqLyricModelDecoder.invokeNoArg(bean, "c"),
            QqLyricModelDecoder.readField(bean, "b")
        )
        val translation = firstNonNull(
            QqLyricModelDecoder.invokeNoArg(bean, "h"),
            QqLyricModelDecoder.readField(bean, "c")
        )
        return read(songInfo) to LyricModels(primary = primary, translation = translation)
    }

    data class LyricModels(
        val primary: Any?,
        val translation: Any?
    )

    private fun stringify(value: Any?): String? = when (value) {
        null -> null
        is Number -> value.toLong().takeIf { it != 0L }?.toString()
        else -> value.toString().trim().takeIf { it.isNotBlank() && it != "0" && it != "1" }
    }

    private fun firstNonPlaceholder(values: List<String?>): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun firstNonNull(vararg values: Any?): Any? = values.firstOrNull { it != null }
}
