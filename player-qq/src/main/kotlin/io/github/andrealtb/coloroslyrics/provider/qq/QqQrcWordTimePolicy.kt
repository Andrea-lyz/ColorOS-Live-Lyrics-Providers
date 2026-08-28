/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import kotlin.math.max

/**
 * QQ QRC normally stores per-word times on the absolute playback timeline.
 * Some malformed/cache-derived payloads instead contain line-relative offsets.
 * The coordinate system must be chosen for the whole line so early absolute
 * timestamps are never shifted by the line begin a second time.
 */
object QqQrcWordTimePolicy {
    private const val RELATIVE_QRC_WORD_TIME_GRACE_MS = 2_000L

    fun resolveForQQMusicQrc(
        lineBegin: Long,
        lineEnd: Long,
        lineDuration: Long,
        rawWordBegins: List<Long>
    ): QqQrcWordTimeAxis {
        val lineSpan = max(lineDuration, lineEnd - lineBegin)
        val relativeTimeLimit = max(
            lineSpan + RELATIVE_QRC_WORD_TIME_GRACE_MS,
            RELATIVE_QRC_WORD_TIME_GRACE_MS
        )
        val usesRelativeOffsets = lineBegin > 0L &&
            rawWordBegins.any { it < lineBegin } &&
            rawWordBegins.all { it in 0L..relativeTimeLimit }
        return QqQrcWordTimeAxis(lineBegin, usesRelativeOffsets)
    }
}

data class QqQrcWordTimeAxis(
    private val lineBegin: Long,
    val usesRelativeOffsets: Boolean
) {
    fun toAbsolute(rawWordTime: Long): Long {
        return if (usesRelativeOffsets) lineBegin + rawWordTime else rawWordTime
    }
}
