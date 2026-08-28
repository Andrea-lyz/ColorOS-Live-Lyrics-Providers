/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

object NeteasePlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.netease"
    const val HOST_PACKAGE = "com.netease.cloudmusic"
    const val NETEASE_PLAY_PROCESS = "$HOST_PACKAGE:play"
    const val HONOR_HOST_PACKAGE = "com.hihonor.cloudmusic"
    const val HONOR_PLAY_PROCESS = "$HONOR_HOST_PACKAGE:play"
    const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    const val LYRIC_INFO_CLASS = "com.netease.cloudmusic.meta.LyricInfo"
    const val MUSIC_INFO_CLASS = "com.netease.cloudmusic.meta.MusicInfo"
    const val LYRIC_HANDLER_WHAT = 16
    const val BLANK_OVERLAY_LOG_LIMIT = 8

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE, HONOR_HOST_PACKAGE)
}
