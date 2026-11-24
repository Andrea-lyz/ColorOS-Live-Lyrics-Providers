/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

/**
 * QQ obfuscated getter names shift between releases, so every candidate is
 * type-checked: a name that now returns another type is skipped, not stringified.
 *
 * - songId (SongInfo field `b`, the value QQ writes as lyricInfo `songId`):
 *   21.0.0.8 `I2()`; 20.7.5.8 `H2()` (21.0 `H2()` returns an ID3 object).
 * - title: 21.0.0.8 `Y3()`; 20.7.5.8 `j3()` (21.0 `j3()` returns int, 20.7 `Y3()` a List).
 * - artist: 21.0.0.8 `Z3()`; 20.7.5.8 `V3()` (20.7 `Z3()` returns a List).
 */
object QqSongInfoReader {
    private val SONG_ID_METHODS = listOf("I2", "H2", "getSongId", "getId")
    private val TITLE_METHODS = listOf("Y3", "j3", "getTitle")
    private val ARTIST_METHODS = listOf("Z3", "V3", "getArtist")

    fun read(songInfo: Any?): TrackIdentity {
        if (songInfo == null) return TrackIdentity()
        val songId = firstNonPlaceholder(
            SONG_ID_METHODS.map { stringifyId(QqLyricModelDecoder.invokeNoArg(songInfo, it)) }
        )
        val title = firstNonPlaceholder(
            TITLE_METHODS.map { stringifyText(QqLyricModelDecoder.invokeNoArg(songInfo, it)) }
        )
        val artist = firstNonPlaceholder(
            ARTIST_METHODS.map { stringifyText(QqLyricModelDecoder.invokeNoArg(songInfo, it)) }
        )
        return TrackIdentity(
            id = songId,
            title = title,
            artist = artist
        )
    }

    /**
     * `LyricLoadBean` keeps its field layout across 20.7.5.8 and 21.0.0.8
     * (`a` songInfo, `b` lyric, `c` transLyric, `d` romaLyric), while its getters
     * shifted: 21.0 `h()` returns SongInfo and `f()` multiStyleLyric. Read the
     * fields; never resolve these by getter name.
     */
    fun readFromLoadBean(bean: Any?): Pair<TrackIdentity, LyricModels> {
        val songInfo = QqLyricModelDecoder.readField(bean, "a")
        val primary = QqLyricModelDecoder.readField(bean, "b")
        val translation = QqLyricModelDecoder.readField(bean, "c")
        return read(songInfo) to LyricModels(primary = primary, translation = translation)
    }

    data class LyricModels(
        val primary: Any?,
        val translation: Any?
    )

    private fun stringifyId(value: Any?): String? = when (value) {
        is Long, is Int -> (value as Number).toLong().takeIf { it > 0L }?.toString()
        is CharSequence -> value.toString().trim()
            .takeIf { it.isNotBlank() && it != "0" && it != "1" }
        else -> null
    }

    private fun stringifyText(value: Any?): String? = when (value) {
        is CharSequence -> value.toString().trim().takeIf(String::isNotBlank)
        else -> null
    }

    private fun firstNonPlaceholder(values: List<String?>): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
