/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

object QishuiPlayerConstants {
    const val MODULE_PACKAGE = "io.github.andrealtb.coloroslyrics.provider.qishui"
    const val HOST_PACKAGE = "com.luna.music"
    const val COMPONENT = "provider/qishui"
    const val SOURCE = "com.luna.music-v5"
    const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    const val CORE_REMOTE_CONTROL_CLASS =
        "com.luna.biz.playing.player.remote.control.CoreRemoteControl"
    val QUALIFIED_HOST_PACKAGES = arrayOf(HOST_PACKAGE)

    fun isPlaybackProcess(processName: String?): Boolean = processName == HOST_PACKAGE

    fun isCastSessionTag(tag: String?): Boolean {
        val value = tag.orEmpty().lowercase()
        return value.contains("cast") || value.contains("chromecast") || value.contains("gms")
    }
}
