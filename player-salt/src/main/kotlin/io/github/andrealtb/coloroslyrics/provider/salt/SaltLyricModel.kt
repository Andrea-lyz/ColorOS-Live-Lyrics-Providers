/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

data class SaltLyricResult(
    val sourceName: String,
    val timedLyric: String,
    val rawCandidate: String,
    val lines: List<RichLyricLine>
)

data class SaltPublication(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourceName: String,
    val timedLyric: String,
    val rawCandidate: String,
    val lines: List<RichLyricLine>
)
