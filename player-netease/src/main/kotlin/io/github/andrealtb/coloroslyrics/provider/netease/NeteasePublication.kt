/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

data class NeteasePublication(
    val track: TrackIdentity,
    val lines: List<RichLyricLine>,
    val generation: Long,
    val captureOrigin: String,
    val payloadMode: NeteasePayloadMode
)
