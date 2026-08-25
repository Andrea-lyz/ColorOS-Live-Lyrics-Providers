/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

object SaltPlayerConstants {
    const val SALT_PACKAGE = "com.salt.music"
    const val SALT_SONG_CLASS = "com.salt.music.data.entry.Song"
    const val MEDIA_BUTTON_RECEIVER_CLASS = "com.salt.music.service.MediaButtonIntentReceiver"
    const val MUSIC_SERVICE_CLASS = "com.salt.music.service.MusicService"
    const val ACTION_PLAY_OR_PAUSE = "com.salt.music.play_or_pause"
    const val ACTION_DESKTOP_LYRICS = "com.salt.music.desktop_lyrics"

    fun lyricModelPackages(): Array<String> =
        arrayOf("androidx.obf", "androidx.media3")

    const val SOURCE_MARKER_EMBEDDED = "EMBEDDED"
    const val SOURCE_MARKER_LYRICS3 = "TAG_LYRICS3_V2"
    const val SCROLL_MARKER_CAN_SCROLL = "CAN_SCROLL"
    const val SCROLL_MARKER_NOT_SCROLL = "NOT_SCROLL"

    const val MEDIA_BUTTON_DEBOUNCE_MS = 1_200L
    const val PLAY_AFTER_SERVICE_START_DELAY_MS = 600L
}
