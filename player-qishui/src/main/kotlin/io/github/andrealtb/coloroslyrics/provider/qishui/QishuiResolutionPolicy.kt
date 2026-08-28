/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

object QishuiResolutionPolicy {
    const val MAX_ATTEMPTS = 8
    const val NEGATIVE_CACHE_TTL_MS = 15_000L

    private val delaysBeforeAttempt = longArrayOf(
        0L,
        400L,
        700L,
        1_100L,
        1_700L,
        2_500L,
        3_500L,
        4_500L
    )

    fun delayBeforeAttempt(attempt: Int): Long? = delaysBeforeAttempt.getOrNull(attempt)
}
