/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.ttml

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import kotlinx.serialization.Serializable

@Serializable
data class TtmlResult(
    val plainLrc: String,
    val enhancedLrc: String,
    val lines: List<RichLyricLine> = emptyList()
)
