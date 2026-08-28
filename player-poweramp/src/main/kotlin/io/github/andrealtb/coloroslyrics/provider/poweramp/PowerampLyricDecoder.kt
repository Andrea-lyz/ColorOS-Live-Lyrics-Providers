/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.LyricLaneAlignmentPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.EnhanceLrcParser

object PowerampLyricDecoder {
    private val TIMED_LRC_REGEX =
        Regex("""[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]""")

    fun containsTimedLrc(value: String?): Boolean =
        !value.isNullOrBlank() && TIMED_LRC_REGEX.containsMatchIn(value)

    /**
     * Sidecar / TagLib LRC often stores translation as a second line with the same
     * timestamp. [EnhanceLrcParser] merges that into `RichLyricLine.secondary`.
     * [LyricLaneAlignmentPolicy.align] overwrites secondary from its translation
     * argument, so it is only used when a separate timed translation lane exists.
     */
    fun decode(
        lyric: String?,
        translation: String? = null,
        capturedTrack: TrackIdentity? = null,
        sourceName: String = "local",
        durationMs: Long = 0L
    ): PowerampPublication? {
        val rawLyric = lyric.orEmpty()
        if (!containsTimedLrc(rawLyric)) return null

        val primary = EnhanceLrcParser.parse(rawLyric, durationMs).lines
        if (primary.none { !it.text.isNullOrBlank() }) return null

        val aligned = if (containsTimedLrc(translation)) {
            LyricLaneAlignmentPolicy.align(
                primary,
                EnhanceLrcParser.parse(translation, durationMs).lines
            )
        } else {
            primary
        }
        if (aligned.none { !it.text.isNullOrBlank() }) return null

        return PowerampPublication(
            rawLyric = rawLyric,
            translationLyric = translation.orEmpty(),
            lines = aligned,
            capturedTrack = capturedTrack?.takeUnless { it.isBlank },
            sourceName = sourceName
        )
    }

    fun matchesTrackIdentity(
        capturedTrack: TrackIdentity?,
        metadataTrack: TrackIdentity?
    ): Boolean {
        val captured = capturedTrack?.takeUnless { it.isBlank } ?: return true
        return TrackIdentityPolicy.isSameTrack(captured, metadataTrack)
    }
}
