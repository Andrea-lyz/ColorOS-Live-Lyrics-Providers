/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.LyricLaneAlignmentPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.EnhanceLrcParser

/**
 * Decodes LX Native `LyricModule#setLyric` text.
 *
 * Argument 0 is the main lyric (standard or enhanced LRC). Argument 1 is translation.
 * Argument 2 is romaji and must never become the translation lane.
 */
object LxLyricDecoder {
    private val TIMED_LRC_REGEX =
        Regex("""[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]""")

    fun containsTimedLrc(value: String?): Boolean =
        !value.isNullOrBlank() && TIMED_LRC_REGEX.containsMatchIn(value)

    fun decode(
        lyric: String?,
        translation: String?,
        capturedTrack: TrackIdentity? = null
    ): LxPublication? {
        val rawLyric = lyric.orEmpty()
        if (!containsTimedLrc(rawLyric)) return null

        val primary = EnhanceLrcParser.parse(rawLyric).lines
        if (primary.none { !it.text.isNullOrBlank() }) return null

        val translationLines = if (containsTimedLrc(translation)) {
            EnhanceLrcParser.parse(translation).lines
        } else {
            emptyList()
        }
        val aligned = LyricLaneAlignmentPolicy.align(primary, translationLines)
        if (aligned.none { !it.text.isNullOrBlank() }) return null

        return LxPublication(
            rawLyric = rawLyric,
            translationLyric = translation.orEmpty(),
            lines = aligned,
            capturedTrack = capturedTrack?.takeUnless { it.isBlank }
        )
    }

    /**
     * Lyrics captured before any MediaSession metadata may bind when metadata first arrives.
     * Once bound to a track, they must never be published for another one.
     */
    fun matchesTrackIdentity(
        capturedTrack: TrackIdentity?,
        metadataTrack: TrackIdentity?
    ): Boolean {
        val captured = capturedTrack?.takeUnless { it.isBlank } ?: return true
        return TrackIdentityPolicy.isSameTrack(captured, metadataTrack)
    }
}
