/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

object SpotifyRetryPolicy {
    val HEADER_WAIT_DELAYS_MS = longArrayOf(150L, 300L, 600L, 1_200L, 2_400L)
    const val MAX_UNAUTHORIZED_RETRIES = 1

    fun nextHeaderWaitDelayMs(attempt: Int): Long? = HEADER_WAIT_DELAYS_MS.getOrNull(attempt)
}
