/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

/**
 * Android-free values for version-4 direct external-lyric broadcasts.
 *
 * <p>Provider code prepares a payload locally, then {@code SystemUiBroadcastSender} adds the
 * static source/player claims and sends one explicit broadcast to SystemUI.</p>
 */
object ExternalLyricV4Protocol {
    const val PROTOCOL_VERSION = 4

    const val ACTION_DIRECT_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_DIRECT_V4"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val SENDER_KIND_PROVIDER = "provider"
    const val SENDER_KIND_MODULE = "module"

    const val EXTRA_PROTOCOL_VERSION = "protocolVersion"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_PLAYER_PACKAGE = "playerPackage"
    const val EXTRA_SENDER_PACKAGE = "senderPackage"
    const val EXTRA_CAPABILITIES = "capabilities"
    const val EXTRA_MATCH_POLICY = "matchPolicy"
    const val EXTRA_EVENT_TYPE = "eventType"
    const val EXTRA_TRACK_GENERATION = "trackGeneration"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_MEDIA_ID = "mediaId"
    const val EXTRA_TRACK_KEY = "trackKey"
    const val EXTRA_SONG_NAME = "songName"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_LYRIC = "lyric"
    const val EXTRA_RAW_LYRIC = "rawLyric"
    const val EXTRA_TRANSLATION_LYRIC = "translationLyric"
    const val EXTRA_CAPTURED_AT = "capturedAt"
    const val EXTRA_SENDER_KIND = "senderKind"

    const val EVENT_TRACK_CHANGED = "trackChanged"
    const val EVENT_LYRIC_READY = "lyricReady"

    const val CAPABILITY_TRACK_GENERATION = "trackGeneration"
    const val CAPABILITY_TRANSLATION_TOGGLE = "translationToggle"
}
