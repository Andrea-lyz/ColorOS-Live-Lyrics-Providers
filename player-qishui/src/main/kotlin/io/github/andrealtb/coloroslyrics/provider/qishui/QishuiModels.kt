/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

data class QishuiPublication(
    val track: TrackIdentity,
    val lines: List<RichLyricLine>,
    val sourceName: String
)

data class QishuiReplaySnapshot(
    val publication: QishuiPublication,
    val generation: Long
)

data class QishuiTrackAuthority(
    val track: TrackIdentity,
    val generation: Long
)
