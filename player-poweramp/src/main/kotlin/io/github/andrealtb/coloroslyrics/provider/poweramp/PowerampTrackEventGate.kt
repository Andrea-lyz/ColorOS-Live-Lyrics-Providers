/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

/**
 * Deduplicates the same Poweramp TRACK_CHANGED intent when the in-process send
 * hook and the non-exported receiver both observe it.
 */
internal class PowerampTrackEventGate {
    private var lastTimestampMillis = Long.MIN_VALUE
    private var lastTrackKey = ""

    @Synchronized
    fun shouldHandle(eventTimestampMillis: Long, trackKey: String): Boolean {
        if (eventTimestampMillis == Long.MIN_VALUE) return true
        if (eventTimestampMillis == lastTimestampMillis && trackKey == lastTrackKey) {
            return false
        }
        lastTimestampMillis = eventTimestampMillis
        lastTrackKey = trackKey
        return true
    }
}
