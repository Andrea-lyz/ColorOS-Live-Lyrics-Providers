/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * Keeps display-level cleanup in Bridge/SystemUI, where user configuration is available.
 * Providers only discard blank rows and normalize source ordering before serialization.
 */
fun retainBridgeLyricLines(lines: List<RichLyricLine>?): List<RichLyricLine> {
    return retainBridgeLyricLines(lines.orEmpty(), { it.text }, { it.begin })
}

fun <T> retainBridgeLyricLines(
    lines: Iterable<T>,
    textOf: (T) -> String?,
    beginOf: (T) -> Long
): List<T> {
    return lines
        .filter { !textOf(it).isNullOrBlank() }
        .sortedBy(beginOf)
}
