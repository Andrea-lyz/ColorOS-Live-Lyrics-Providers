/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

/**
 * Distinguishes LX/Walnut Bluetooth lyric projection from an actual MediaSession track change.
 *
 * Two player settings rewrite MediaSession without a track change:
 * - "显示蓝牙歌词": TITLE becomes the current lyric line (or blank while seeking) and ARTIST
 *   is `"${songName} - ${singer}"`.
 * - "显示完整蓝牙歌词": TITLE becomes the full LRC dump.
 *
 * ColorOS lockscreen uses TITLE/ARTIST as the SystemUI track key. Projection metadata must
 * resolve back to the real song identity (not the lyric line) before generation or replay.
 */
object LxBluetoothLyricMetadataPolicy {

    data class Resolution(
        val track: TrackIdentity,
        val projection: Boolean
    )

    data class ParsedProjection(
        val title: String,
        val artist: String
    ) {
        fun toTrack(source: TrackIdentity): TrackIdentity = TrackIdentity(
            title = title,
            artist = artist.takeIf { it.isNotEmpty() },
            album = source.album,
            durationMs = source.durationMs
        )
    }

    fun resolve(
        stableTrack: TrackIdentity?,
        candidate: TrackIdentity?
    ): Resolution? {
        if (candidate == null || candidate.isBlank) {
            return stableTrack?.let { Resolution(it, projection = true) }
        }
        val parsed = parseProjection(candidate)
        if (parsed != null) {
            val parsedTrack = parsed.toTrack(candidate)
            if (stableTrack != null && sameSong(stableTrack, parsedTrack)) {
                return Resolution(stableTrack, projection = true)
            }
            // A new song may already arrive in `"${name} - ${singer}"` form. Decode it
            // from LX's own ARTIST encoding instead of treating the lyric TITLE as a track.
            return Resolution(parsedTrack, projection = true)
        }
        if (isBluetoothLyricProjection(stableTrack, candidate)) {
            return Resolution(stableTrack!!, projection = true)
        }
        return Resolution(candidate, projection = false)
    }

    fun parseProjection(candidate: TrackIdentity): ParsedProjection? {
        val candidateTitle = candidate.title.orEmpty().trim()
        val candidateArtist = candidate.artist.orEmpty().trim()
        if (LxLyricDecoder.containsTimedLrc(candidateTitle)) {
            return splitSongAndSinger(candidateArtist)
        }
        val split = splitSongAndSinger(candidateArtist) ?: return null
        if (candidateTitle.equals(split.title, ignoreCase = true)) {
            return null
        }
        return split
    }

    fun splitSongAndSinger(artist: String?): ParsedProjection? {
        val value = artist?.trim().orEmpty()
        val index = value.lastIndexOf(" - ")
        if (index <= 0) return null
        val title = value.substring(0, index).trim()
        val singer = value.substring(index + 3).trim()
        if (title.isEmpty()) return null
        return ParsedProjection(title, singer)
    }

    fun isBluetoothLyricProjection(
        stableTrack: TrackIdentity?,
        candidate: TrackIdentity
    ): Boolean {
        if (parseProjection(candidate) != null) return true
        if (stableTrack == null) return false

        val stableTitle = stableTrack.title.orEmpty().trim()
        val stableArtist = stableTrack.artist.orEmpty().trim()
        val candidateTitle = candidate.title.orEmpty().trim()
        val candidateArtist = candidate.artist.orEmpty().trim()
        if (stableTitle.isEmpty()) {
            return false
        }
        if (candidateTitle == stableTitle && candidateArtist == stableArtist) {
            return false
        }

        if (LxLyricDecoder.containsTimedLrc(candidateTitle)) {
            return true
        }

        if (candidateArtist.isEmpty()) {
            return false
        }

        if (stableArtist.isEmpty()) return candidateArtist == stableTitle

        val titlePrefix = "$stableTitle - "
        if (!candidateArtist.startsWith(titlePrefix)) return false

        val projectedArtist = candidateArtist.removePrefix(titlePrefix)
        return projectedArtist == stableArtist
            || stableArtist.startsWith("$projectedArtist - ")
            || projectedArtist.startsWith("$stableArtist - ")
    }

    fun sameSong(left: TrackIdentity, right: TrackIdentity): Boolean {
        val withoutIds = TrackIdentity(
            title = right.title,
            artist = right.artist,
            album = right.album,
            durationMs = right.durationMs
        )
        return TrackIdentityPolicy.isSameTrack(
            left.copy(id = null),
            withoutIds
        )
    }
}

object LxSessionIdentity {
    fun shouldRewrite(
        incomingTitle: String?,
        incomingArtist: String?,
        resolvedTitle: String?,
        resolvedArtist: String?
    ): Boolean {
        val resolved = resolvedTitle?.trim().orEmpty()
        if (resolved.isEmpty()) return false
        return incomingTitle?.trim().orEmpty() != resolved ||
            incomingArtist?.trim().orEmpty() != resolvedArtist?.trim().orEmpty()
    }
}
