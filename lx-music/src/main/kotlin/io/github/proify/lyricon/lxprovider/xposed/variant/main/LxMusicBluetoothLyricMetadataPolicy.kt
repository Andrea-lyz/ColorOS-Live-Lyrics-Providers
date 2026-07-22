/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

import io.github.proify.lyricon.lxprovider.xposed.Metadata

/**
 * Distinguishes LX's Bluetooth lyric projection from an actual MediaSession track change.
 *
 * When "show Bluetooth lyrics" is enabled, LX repeatedly replaces MediaSession's title with
 * the active lyric line and writes the original song identity into the artist field. Those
 * updates must not advance the Bridge track generation.
 */
object LxMusicBluetoothLyricMetadataPolicy {
    @JvmStatic
    fun isBluetoothLyricProjection(
        stableTrack: Metadata?,
        candidate: Metadata,
        hasBridgeLyrics: Boolean
    ): Boolean {
        if (!hasBridgeLyrics || stableTrack == null) return false

        val stableTitle = stableTrack.title.orEmpty().trim()
        val stableArtist = stableTrack.artist.orEmpty().trim()
        val candidateTitle = candidate.title.orEmpty().trim()
        val candidateArtist = candidate.artist.orEmpty().trim()
        if (stableTitle.isEmpty() || candidateArtist.isEmpty()) {
            return false
        }
        if (candidateTitle == stableTitle && candidateArtist == stableArtist) {
            return false
        }

        // While the seek bar is being moved, LX may briefly publish an empty lyric line while
        // retaining the Bluetooth projection artist ("<song> - <artist>"). It is still the
        // same track; treating it as new metadata poisons the stable identity, and the following
        // non-empty lyric lines then look like separate tracks to the Bridge.

        if (stableArtist.isEmpty()) return candidateArtist == stableTitle

        val titlePrefix = "$stableTitle - "
        if (!candidateArtist.startsWith(titlePrefix)) return false

        val projectedArtist = candidateArtist.removePrefix(titlePrefix)
        return projectedArtist == stableArtist
            || stableArtist.startsWith("$projectedArtist - ")
            || projectedArtist.startsWith("$stableArtist - ")
    }
}
