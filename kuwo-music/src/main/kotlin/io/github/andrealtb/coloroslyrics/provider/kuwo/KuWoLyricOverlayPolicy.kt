/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

internal enum class KuWoLyricOverlayAction {
    /** Write the freshly encoded lyricInfo into the host metadata. */
    APPEND,

    /** Drop a lyricInfo that belongs to a different track. */
    CLEAR,

    /** Leave the host metadata exactly as it is. */
    NOOP
}

/**
 * Pure decision table for the append-only overlay. Keeping it separate from the reflection and
 * parcel plumbing makes the exact write conditions unit-testable.
 */
internal object KuWoLyricOverlayPolicy {
    fun decide(
        songAvailable: Boolean,
        trackMatches: Boolean,
        currentValue: String?,
        newValue: String?
    ): KuWoLyricOverlayAction {
        if (!songAvailable) return KuWoLyricOverlayAction.NOOP
        if (!trackMatches) {
            return if (currentValue.isNullOrEmpty()) {
                KuWoLyricOverlayAction.NOOP
            } else {
                KuWoLyricOverlayAction.CLEAR
            }
        }
        val candidate = newValue.orEmpty()
        if (candidate.isEmpty() || candidate == currentValue) {
            return KuWoLyricOverlayAction.NOOP
        }
        return KuWoLyricOverlayAction.APPEND
    }
}
