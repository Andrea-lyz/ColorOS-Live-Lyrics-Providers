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

    /** Salt 12.7.0+ MEDIA_ID fallback form: "song:" + Song.id. */
    const val MEDIA_ID_SONG_PREFIX = "song:"
    /** Salt 12.7.0+ MEDIA_ID queue form: queueIndex - Int.MIN_VALUE. */
    const val QUEUE_MEDIA_ID_MIN = 2_147_483_648L
    const val QUEUE_MEDIA_ID_END = 4_294_967_296L

    const val MEDIA_BUTTON_DEBOUNCE_MS = 1_200L
    const val PLAY_AFTER_SERVICE_START_DELAY_MS = 600L
}
