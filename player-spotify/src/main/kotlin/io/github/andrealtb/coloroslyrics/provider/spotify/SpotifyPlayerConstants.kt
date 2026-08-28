/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

object SpotifyPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.spotify"
    const val HOST_PACKAGE = "com.spotify.music"
    const val COMPONENT = "provider/spotify"
    const val TRACK_URI_PREFIX = "spotify:track:"
    const val EPISODE_URI_PREFIX = "spotify:episode:"
    const val SHOW_URI_PREFIX = "spotify:show:"
    const val COLOR_LYRICS_BASE_URL =
        "https://guc3-spclient.spotify.com/color-lyrics/v2/track/"
    const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)

    fun isPlaybackProcess(processName: String?): Boolean = processName == HOST_PACKAGE

    fun isCastSessionTag(tag: String?): Boolean {
        val normalized = tag.orEmpty().lowercase()
        if (normalized.isBlank()) return false
        return normalized.contains("cast") || normalized.contains("gms")
    }
}
