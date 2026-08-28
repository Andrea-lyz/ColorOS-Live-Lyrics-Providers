/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import kotlin.math.abs

/**
 * Host Metrolist stores duration in seconds, or -1 when unknown. Query duration
 * 0 / negative means "ignore duration", matching KuGou's own `duration == -1`
 * wildcard. Platform MediaSession milliseconds must be converted before this.
 */
object MetrolistKuGouMatchPolicy {
    const val DURATION_TOLERANCE_SECONDS = 8

    fun queryDurationSeconds(durationMs: Long): Int =
        if (durationMs > 0L) (durationMs / 1000L).toInt() else 0

    fun matchesSongDuration(songDurationSeconds: Int, queryDurationSeconds: Int): Boolean {
        if (queryDurationSeconds <= 0) return true
        return abs(songDurationSeconds - queryDurationSeconds) <= DURATION_TOLERANCE_SECONDS
    }
}
