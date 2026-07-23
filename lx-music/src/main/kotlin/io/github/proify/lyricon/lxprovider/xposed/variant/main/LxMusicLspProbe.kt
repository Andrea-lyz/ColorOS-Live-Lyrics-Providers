/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lxprovider.xposed.Metadata

/**
 * Low-frequency LX transport diagnostics for LSPosed logs.
 *
 * Track fields are represented by length/hash fingerprints so a user can correlate a transition
 * without exposing titles, artists, or lyric text in a release log.
 */
internal object LxMusicLspProbe {
    private const val TAG = "LXMusicProbe"

    fun event(name: String, details: String) {
        YLog.debug(tag = TAG, msg = "[LX_PROBE] $name | $details")
    }

    fun track(metadata: Metadata?): String {
        if (metadata == null) return "track=none"
        return "mediaId=${token(metadata.id)} title=${token(metadata.title)} " +
            "artist=${token(metadata.artist)} duration=${metadata.duration}"
    }

    fun token(value: String?): String {
        val text = value.orEmpty()
        return "${text.length}:${Integer.toHexString(text.hashCode())}"
    }
}
