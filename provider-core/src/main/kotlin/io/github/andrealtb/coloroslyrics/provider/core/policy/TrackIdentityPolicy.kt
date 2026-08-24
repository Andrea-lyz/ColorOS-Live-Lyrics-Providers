/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.policy

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import kotlin.math.abs

object TrackIdentityPolicy {

    fun isSameTrack(previous: TrackIdentity?, current: TrackIdentity?): Boolean {
        if (previous == null || current == null) return false
        if (previous.isBlank || current.isBlank) return false

        if (!previous.id.isNullOrBlank() && !current.id.isNullOrBlank()) {
            if (previous.id.trim() == current.id.trim()) return true
        }

        val titleMatch = normalizeString(previous.title) == normalizeString(current.title)
        val artistMatch = normalizeString(previous.artist) == normalizeString(current.artist)

        if (titleMatch && artistMatch) {
            if (previous.durationMs > 0 && current.durationMs > 0) {
                return abs(previous.durationMs - current.durationMs) <= 3000L
            }
            return true
        }

        return false
    }

    fun hasTrackChanged(previous: TrackIdentity?, current: TrackIdentity?): Boolean =
        !isSameTrack(previous, current)

    private fun normalizeString(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim()
            .lowercase()
            .replace(Regex("""\s+"""), " ")
    }
}
