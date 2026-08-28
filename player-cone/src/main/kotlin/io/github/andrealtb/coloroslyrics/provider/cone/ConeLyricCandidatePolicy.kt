/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.EnhanceLrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

enum class ConeLyricSource(val priority: Int) {
    BROADCAST(100),
    PARSER(80),
    TRACK_METADATA(60),
    LYRICON(40)
}

data class ConeLyricCandidate(
    val source: ConeLyricSource,
    val rawLyric: String,
    val lines: List<RichLyricLine>,
    val trackHint: TrackIdentity? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class ConeLyricCandidatePolicy {
    private var currentTrack: TrackIdentity? = null
    private var bestCandidate: ConeLyricCandidate? = null

    @Synchronized
    fun evaluate(
        source: ConeLyricSource,
        rawLyric: String,
        trackHint: TrackIdentity? = null
    ): ConeLyricCandidate? {
        if (!ConeLyricFilter.isUsableTimedLyric(rawLyric)) {
            return null
        }

        if (trackHint != null && currentTrack != null && !TrackIdentityPolicy.isSameTrack(trackHint, currentTrack)) {
            currentTrack = trackHint
            bestCandidate = null
        } else if (currentTrack == null && trackHint != null) {
            currentTrack = trackHint
        }

        val currentBest = bestCandidate
        if (currentBest != null && currentBest.source.priority > source.priority) {
            return null
        }

        if (currentBest != null && currentBest.source == source && currentBest.rawLyric == rawLyric) {
            return null
        }

        val doc = EnhanceLrcParser.parse(rawLyric)
        if (doc.lines.isEmpty() || doc.lines.none { !it.text.isNullOrBlank() }) {
            return null
        }

        val candidate = ConeLyricCandidate(
            source = source,
            rawLyric = rawLyric,
            lines = doc.lines,
            trackHint = trackHint
        )
        bestCandidate = candidate
        return candidate
    }

    @Synchronized
    fun onTrackChanged(newTrack: TrackIdentity?) {
        if (!TrackIdentityPolicy.isSameTrack(currentTrack, newTrack)) {
            currentTrack = newTrack
            bestCandidate = null
        }
    }

    @Synchronized
    fun clear() {
        currentTrack = null
        bestCandidate = null
    }

    @Synchronized
    fun peekBest(): ConeLyricCandidate? = bestCandidate
}
