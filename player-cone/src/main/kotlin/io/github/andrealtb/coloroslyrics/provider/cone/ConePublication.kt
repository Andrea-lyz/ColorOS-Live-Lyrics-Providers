/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

data class ConePublication(
    val source: ConeLyricSource,
    val rawLyric: String,
    val lines: List<RichLyricLine>,
    val trackHint: TrackIdentity? = null,
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L
) {
    fun trackIdentity(): TrackIdentity = TrackIdentity(
        id = songId.takeIf(String::isNotBlank) ?: trackHint?.id,
        title = title.takeIf(String::isNotBlank) ?: trackHint?.title,
        artist = artist.takeIf(String::isNotBlank) ?: trackHint?.artist,
        album = album.takeIf(String::isNotBlank) ?: trackHint?.album,
        durationMs = if (durationMs > 0L) durationMs else trackHint?.durationMs ?: 0L
    )
}
