/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

/**
 * Bridge payload lyrics captured from LX's native LyricModule.
 *
 * The raw lane stays untouched so an enhanced LRC already supplied by LX reaches Bridge with its
 * original word timestamps. The display lane only removes inline word-time tags; it never creates
 * synthetic word timing.
 */
internal data class LxMusicBridgeLyrics(
    val rawLyric: String,
    val lyric: String,
    val translationLyric: String
) {
    companion object {
        private val timedLrcRegex =
            Regex("""[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]""")
        private val inlineWordTimeRegex =
            Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

        fun from(
            lyric: String?,
            translation: String?
        ): LxMusicBridgeLyrics? {
            val rawLyric = lyric.orEmpty()
            if (!containsTimedLrc(rawLyric)) return null

            val plainLyric = rawLyric
                .lineSequence()
                .joinToString("\n") { line -> line.replace(inlineWordTimeRegex, "") }
                .trim()
                .ifBlank { rawLyric }
            val translationLyric = translation.orEmpty()
                .takeIf(::containsTimedLrc)
                .orEmpty()
            return LxMusicBridgeLyrics(rawLyric, plainLyric, translationLyric)
        }

        internal fun containsTimedLrc(value: String): Boolean {
            return timedLrcRegex.containsMatchIn(value)
        }

        /**
         * A lyric captured before any MediaSession metadata may be published when metadata first
         * arrives. Once it is bound to a track, though, it must never be replayed for another one.
         */
        @JvmStatic
        fun matchesTrackIdentity(
            capturedTrackIdentity: String,
            metadataTrackIdentity: String
        ): Boolean {
            return capturedTrackIdentity.isBlank() ||
                capturedTrackIdentity == metadataTrackIdentity
        }
    }
}
