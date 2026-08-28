/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

data class PowerampPublication(
    val rawLyric: String,
    val translationLyric: String,
    val lines: List<RichLyricLine>,
    val capturedTrack: TrackIdentity? = null,
    val sourceName: String = "local"
) {
    fun trackIdentity(): TrackIdentity =
        capturedTrack?.takeUnless { it.isBlank } ?: TrackIdentity()

    fun boundTo(track: TrackIdentity): PowerampPublication = copy(capturedTrack = track)
}
