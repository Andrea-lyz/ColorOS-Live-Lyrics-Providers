/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

data class ApplePublication(
    val lines: List<RichLyricLine>,
    val capturedTrack: TrackIdentity? = null,
    val sourceName: String = "apple-ttml"
) {
    fun trackIdentity(): TrackIdentity =
        capturedTrack?.takeUnless { it.isBlank } ?: TrackIdentity()

    fun boundTo(track: TrackIdentity): ApplePublication = copy(capturedTrack = track)
}
