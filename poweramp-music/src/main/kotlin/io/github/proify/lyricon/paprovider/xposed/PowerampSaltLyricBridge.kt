/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.proify.extensions.bridge.BridgePayloadGate
import java.util.Locale

internal object PowerampSaltLyricBridge {
    private const val TAG = "Lyricon_PowerampBridge"
    private const val ACTION_EXTERNAL_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_CAPTURED"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val BRIDGE_PROTOCOL_VERSION = 2
    private const val SOURCE_POWERAMP = "lyricprovider/poweramp-music"
    private const val BRIDGE_CAPABILITIES =
        "trackGeneration,currentTrackAuthority,titleOnlyFallback"
    private const val BRIDGE_MATCH_POLICY = "mediaId,trackKey,titleArtist,titleOnly"
    private const val BRIDGE_IDENTITY_CONFIDENCE = "currentTrack"
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val payloadGate = BridgePayloadGate()

    fun sendTrackChanged(
        context: Context?,
        metadata: TrackMetadata,
        trackGeneration: Long
    ) {
        if (context == null || trackGeneration <= 0L) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_POWERAMP)
            putExtra("eventType", "trackChanged")
            putExtra("mediaId", metadata.id)
            putExtra("trackKey", buildTrackKey(metadata.title, metadata.artist))
            putExtra("songName", metadata.title.orEmpty())
            putExtra("artist", metadata.artist.orEmpty())
            putExtra("duration", validDuration(metadata.duration))
            putExtra("trackGeneration", trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onSuccess {
            PowerampLog.debug(
                tag = TAG,
                msg = "Sent Poweramp track change, generation=$trackGeneration, id=${metadata.id}"
            )
        }.onFailure { error ->
            PowerampLog.error(
                tag = TAG,
                msg = "Failed to send Poweramp track change, generation=$trackGeneration",
                e = error
            )
        }
    }

    fun sendLyricReady(
        context: Context?,
        payload: PowerampPreparedLyricPayload
    ) {
        if (context == null || payload.trackGeneration <= 0L) return

        val requestId = "${payload.trackGeneration}:${payload.rawLyric.hashCode().toUInt().toString(16)}"
        val payloadKey = "$SOURCE_POWERAMP:${payload.trackGeneration}:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_POWERAMP)
            putExtra("eventType", "lyricReady")
            putExtra("requestId", requestId)
            putExtra("mediaId", payload.mediaId)
            putExtra("trackKey", payload.trackKey)
            putExtra("songName", payload.title)
            putExtra("artist", payload.artist)
            putExtra("duration", validDuration(payload.duration))
            putExtra("lyric", payload.lyric)
            putExtra("rawLyric", payload.rawLyric)
            putExtra("translationLyric", payload.translationLyric)
            putExtra("trackGeneration", payload.trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onSuccess {
            PowerampLog.debug(
                tag = TAG,
                msg = "Sent Poweramp lyric payload, generation=${payload.trackGeneration}, " +
                    "id=${payload.mediaId}, rawChars=${payload.rawLyric.length}"
            )
        }.onFailure { error ->
            payloadGate.forget(payloadKey)
            PowerampLog.error(
                tag = TAG,
                msg = "Failed to send Poweramp lyric payload, generation=${payload.trackGeneration}",
                e = error
            )
        }
    }

    private fun Intent.putBridgeDeclaration(context: Context) {
        putExtra("protocolVersion", BRIDGE_PROTOCOL_VERSION)
        putExtra("playerPackage", context.packageName)
        putExtra("capabilities", BRIDGE_CAPABILITIES)
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
        putExtra("identityConfidence", BRIDGE_IDENTITY_CONFIDENCE)
    }

    private fun validDuration(duration: Long): Long {
        return duration.takeIf { it in 1..MAX_REASONABLE_DURATION_MS } ?: 0L
    }

    private fun buildTrackKey(title: String?, artist: String?): String {
        val normalizedTitle = normalizeTrackComponent(title)
        if (normalizedTitle.isBlank()) return ""
        return "$normalizedTitle|${normalizeTrackComponent(artist)}"
    }

    private fun normalizeTrackComponent(value: String?): String {
        return WHITESPACE_REGEX.replace(value.orEmpty().trim(), " ")
            .lowercase(Locale.ROOT)
    }
}

internal data class PowerampPreparedLyricPayload(
    val mediaId: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val trackKey: String,
    val trackGeneration: Long,
    val lyric: String,
    val rawLyric: String,
    val translationLyric: String,
    val lyricInfo: String
)
