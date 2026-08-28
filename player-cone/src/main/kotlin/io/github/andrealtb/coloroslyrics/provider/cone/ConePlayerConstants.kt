/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

object ConePlayerConstants {
    const val CONE_PACKAGE = "ink.trantor.coneplayer"
    const val CONE_GP_PACKAGE = "ink.trantor.coneplayer.gp"

    val KNOWN_PACKAGES = arrayOf(CONE_PACKAGE, CONE_GP_PACKAGE)
    val QUALIFIED_HOST_PACKAGES = arrayOf(CONE_PACKAGE, CONE_GP_PACKAGE)

    const val ACTION_CURRENT_LYRIC_CHANGED = "ink.trantor.android.mediaplayer.action.CURRENT_LYRIC_CHANGED"
    const val EXTRA_LYRIC_TEXT = "extra_lyric_text"

    const val MEDIA_PLAYER_SERVICE_CLASS = "ink.trantor.android.mediaplayer.MediaPlayerService"
    const val MEDIA3_TRACKS_CLASS = "androidx.media3.common.Tracks"

    val EMPTY_LYRIC_TEXTS: Set<String> = setOf(
        "暂无歌词",
        "暂无歌词。",
        "无歌词",
        "无歌词。",
        "纯音乐",
        "纯音乐，请欣赏",
        "no lyric",
        "no lyrics",
        "instrumental"
    )

    val LYRIC_METADATA_KEYS: Set<String> = setOf(
        "LYRICS",
        "LYRIC",
        "UNSYNCEDLYRICS",
        "USLT",
        "SYLT"
    )
}
