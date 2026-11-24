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

    /**
     * `CurrentLoadManager#notifyLoadSuc(model.a, int)` is `u0` in both 20.7.5.8 and
     * 21.0.0.8. It drops the translation before building `LyricLoadBean` when the
     * translation line count differs from the lyric; the model still holds it.
     */
    const val LYRIC_LOAD_MANAGER = "com.tencent.qqmusic.business.lyricnew.load.manager.h"
    const val LYRIC_LOAD_MODEL = "com.tencent.qqmusic.business.lyricnew.load.model.a"
    const val NOTIFY_LOAD_SUC_METHOD = "u0"
    const val NOTIFY_TRANS_MISMATCH_LOG = " [notifyLoadSuc] transLy not equals ly"
    const val LYRIC_ENGINE_DOCUMENT = "com.lyricengine.base.k"
    const val SONG_INFO = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
    const val SEEDLING_LYRIC_KEY = "lyricInfo"
    const val SEEDLING_TRANS_KEY = "transLyric"

    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)
}
