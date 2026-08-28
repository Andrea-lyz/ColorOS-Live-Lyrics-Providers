/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

object QqPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.qq"
    const val HOST_PACKAGE = "com.tencent.qqmusic"
    const val PLAYER_PROCESS_SUFFIX = ":QQPlayerService"
    const val SOURCE_INTERNAL = "qqmusic-internal"
    const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    const val REMOTE_LYRIC_CONTROLLER =
        "com.tencent.qqmusicplayerprocess.servicenew.mediasession.RemoteLyricController"
    const val LYRIC_LOAD_BEAN = "com.tencent.qqmusic.business.lyricnew.load.model.b"
    const val LYRIC_ENGINE_DOCUMENT = "com.lyricengine.base.k"
    const val SONG_INFO = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
    const val SEEDLING_LYRIC_KEY = "lyricInfo"
    const val SEEDLING_TRANS_KEY = "transLyric"

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)
}
