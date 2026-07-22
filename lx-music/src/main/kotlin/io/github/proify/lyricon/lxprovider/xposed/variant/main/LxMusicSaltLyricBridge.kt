/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.proify.extensions.android.SystemUiBroadcastSender
import io.github.proify.extensions.bridge.ExternalLyricV4Protocol
import io.github.proify.lyricon.lxprovider.xposed.Metadata
import java.util.Locale

/**
 * Versioned external-lyric transport for the two supported LX Music variants.
 *
 * This object only observes LX state and sends an explicit broadcast to SystemUI. It never calls
 * LX's Bluetooth-lyric APIs and never mutates MediaSession metadata, so LX's own Bluetooth lyric
 * behaviour remains authoritative.
 */
object LxMusicSaltLyricBridge {
    private const val TAG = "Lyricon_LXBridge"
    private const val TOSIDE_PLAYER_PACKAGE = "cn.toside.music.mobile"
    private const val WALNUT_PLAYER_PACKAGE = "com.lxwalnut.music.mobile"
    private const val TOSIDE_SOURCE = "lyricprovider/lx-music"
    private const val WALNUT_SOURCE = "lyricprovider/lx-walnut-music"
    private const val CAPABILITIES =
        ExternalLyricV4Protocol.CAPABILITY_TRACK_GENERATION +
            "," + ExternalLyricV4Protocol.CAPABILITY_TRANSLATION_TOGGLE
    private const val MATCH_POLICY = "mediaId,trackKey,titleArtist"
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L

    fun sourceFor(playerPackage: String?): String? {
        return when (playerPackage) {
            TOSIDE_PLAYER_PACKAGE -> TOSIDE_SOURCE
            WALNUT_PLAYER_PACKAGE -> WALNUT_SOURCE
            else -> null
        }
    }

    fun sendTrackChanged(
        context: Context?,
        source: String,
        metadata: Metadata,
        trackGeneration: Long
    ): Boolean {
        if (!canSend(context, source) || trackGeneration <= 0L || !hasTrackIdentity(metadata)) {
            return false
        }
        val safeContext = context ?: return false
        val intent = baseIntent(source, ExternalLyricV4Protocol.EVENT_TRACK_CHANGED).apply {
            putTrackExtras(this, metadata, trackGeneration)
        }
        return send(safeContext, intent, source, "track change", trackGeneration)
    }

    internal fun sendLyricReady(
        context: Context?,
        source: String,
        metadata: Metadata,
        lyrics: LxMusicBridgeLyrics,
        trackGeneration: Long
    ): Boolean {
        if (!canSend(context, source) || trackGeneration <= 0L || !hasTrackIdentity(metadata)) {
            return false
        }
        val safeContext = context ?: return false
        val requestId = buildRequestId(source, metadata, lyrics)
        val intent = baseIntent(source, ExternalLyricV4Protocol.EVENT_LYRIC_READY).apply {
            putTrackExtras(this, metadata, trackGeneration)
            putExtra(ExternalLyricV4Protocol.EXTRA_REQUEST_ID, requestId)
            putExtra(ExternalLyricV4Protocol.EXTRA_LYRIC, lyrics.lyric)
            putExtra(ExternalLyricV4Protocol.EXTRA_RAW_LYRIC, lyrics.rawLyric)
            putExtra(ExternalLyricV4Protocol.EXTRA_TRANSLATION_LYRIC, lyrics.translationLyric)
        }
        return send(safeContext, intent, source, "lyric payload", trackGeneration)
    }

    private fun canSend(context: Context?, source: String): Boolean {
        return context != null && sourceFor(context.packageName) == source
    }

    private fun baseIntent(
        source: String,
        eventType: String
    ): Intent {
        return Intent().apply {
            putExtra(ExternalLyricV4Protocol.EXTRA_SOURCE, source)
            putExtra(ExternalLyricV4Protocol.EXTRA_CAPABILITIES, CAPABILITIES)
            putExtra(ExternalLyricV4Protocol.EXTRA_MATCH_POLICY, MATCH_POLICY)
            putExtra(ExternalLyricV4Protocol.EXTRA_EVENT_TYPE, eventType)
            putExtra(ExternalLyricV4Protocol.EXTRA_CAPTURED_AT, System.currentTimeMillis())
        }
    }

    private fun putTrackExtras(
        intent: Intent,
        metadata: Metadata,
        trackGeneration: Long
    ) {
        intent.putExtra(ExternalLyricV4Protocol.EXTRA_MEDIA_ID, metadata.id)
        intent.putExtra(ExternalLyricV4Protocol.EXTRA_TRACK_KEY, trackKey(metadata.title, metadata.artist))
        intent.putExtra(ExternalLyricV4Protocol.EXTRA_SONG_NAME, metadata.title.orEmpty())
        intent.putExtra(ExternalLyricV4Protocol.EXTRA_ARTIST, metadata.artist.orEmpty())
        intent.putExtra(ExternalLyricV4Protocol.EXTRA_DURATION, validDuration(metadata.duration))
        intent.putExtra(ExternalLyricV4Protocol.EXTRA_TRACK_GENERATION, trackGeneration)
    }

    private fun hasTrackIdentity(metadata: Metadata): Boolean {
        return metadata.id.isNotBlank() || trackKey(metadata.title, metadata.artist).isNotBlank()
    }

    private fun buildRequestId(
        source: String,
        metadata: Metadata,
        lyrics: LxMusicBridgeLyrics
    ): String {
        val identity = metadata.id.ifBlank { trackKey(metadata.title, metadata.artist) }
        val contentHash = Integer.toHexString(
            (lyrics.rawLyric + '\n' + lyrics.translationLyric).hashCode()
        )
        return "$source:$identity:$contentHash"
    }

    private fun trackKey(title: String?, artist: String?): String {
        val normalizedTitle = normalizeTrackComponent(title)
        if (normalizedTitle.isBlank()) return ""
        return normalizedTitle + "|" + normalizeTrackComponent(artist)
    }

    private fun normalizeTrackComponent(value: String?): String {
        return value.orEmpty()
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
    }

    private fun validDuration(duration: Long): Long {
        return if (duration in 1L..MAX_REASONABLE_DURATION_MS) duration else 0L
    }

    private fun send(
        context: Context,
        intent: Intent,
        source: String,
        event: String,
        trackGeneration: Long
    ): Boolean {
        return runCatching {
            SystemUiBroadcastSender.submit(context, intent, TAG, source)
        }.onSuccess { outcome ->
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(
                    TAG,
                    "Sent LX $event | source=$source generation=$trackGeneration " +
                        "bytes=${outcome.parcelBytes} downgraded=${outcome.downgradedWordTiming}"
                )
            }
        }.onFailure { error ->
            if (SystemUiBroadcastSender.shouldReportFailure(error)) {
                Log.w(
                    TAG,
                    "Failed to send LX $event | source=$source generation=$trackGeneration",
                    error
                )
            }
        }.isSuccess
    }
}
