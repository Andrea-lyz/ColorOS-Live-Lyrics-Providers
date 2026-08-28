/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

object ApplePlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.apple"
    const val HOST_PACKAGE = "com.apple.android.music"
    const val COMPONENT = "provider/apple"

    const val PLAYER_LYRICS_VIEW_MODEL =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
    const val PLAYBACK_ITEM = "com.apple.android.music.model.PlaybackItem"
    const val SONG_INFO_PTR =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"
    const val LOCALE_UTIL = "com.apple.android.music.playback.util.LocaleUtil"
    const val LOAD_LYRICS = "loadLyrics"
    const val BUILD_TIME_RANGE_TO_LYRICS_MAP = "buildTimeRangeToLyricsMap"

    const val METADATA_KEY_MEDIA_ID =
        "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
    const val METADATA_KEY_PLAYBACK_ENDPOINT_TYPE =
        "com.apple.android.music.playback.metadata.METADATA_KEY_PLAYBACK_ENDPOINT_TYPE"

    const val MAPPER_FALLBACK_N = "com.apple.android.music.player.N"
    const val MAPPER_FALLBACK_M = "com.apple.android.music.player.M"
    const val MAPPER_SEARCH_PACKAGE = "com.apple.android.music.player"

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)

    fun isCastSessionTag(tag: String?): Boolean {
        val normalized = tag.orEmpty().lowercase()
        if (normalized.isBlank()) return false
        return normalized.contains("cast") || normalized.contains("gms")
    }
}
