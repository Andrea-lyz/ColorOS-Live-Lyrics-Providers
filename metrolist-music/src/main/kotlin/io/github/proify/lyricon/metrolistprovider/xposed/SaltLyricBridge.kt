/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.proify.extensions.android.SystemUiBroadcastSender
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.extensions.bridge.TrackKeyBuilder

object SaltLyricBridge {
    private const val TAG = "Lyricon_MetrolistBridge"
    private const val SOURCE_METROLIST = "lyricprovider/metrolist-music"
    private const val BRIDGE_CAPABILITIES =
        "playbackState,trackGeneration,currentTrackAuthority"
    private const val BRIDGE_MATCH_POLICY = "mediaId,trackKey,titleArtist"
    private const val EXTRA_PLAYBACK_STATE = "playbackState"
    private const val EXTRA_PLAYBACK_POSITION = "playbackPosition"
    private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
    private const val EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME =
        "playbackLastPositionUpdateTime"
    private const val MAX_DURATION_SECONDS = 24 * 60 * 60
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
    private val INLINE_WORD_TIME_REGEX =
        Regex("""<[0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?>""")
    private val payloadGate = BridgePayloadGate()
    private val playbackStateGate = BridgePlaybackStateGate()

    // The high initial value prevents a provider process restart from being
    // mistaken for an older session by the Bridge's generation reducer.
    private var trackGeneration = System.currentTimeMillis() / 1_000L

    @Synchronized
    fun sendTrackChanged(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        durationSeconds: Int
    ): Long {
        if (songId.isEmpty()) return trackGeneration

        trackGeneration++
        val generation = trackGeneration
        playbackStateGate.reset()
        val trackKey = TrackKeyBuilder.build(title, artist)
        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_METROLIST)
            putExtra("eventType", "trackChanged")
            putExtra("requestId", "metrolist:tc:$songId:$generation")
            putExtra("mediaId", songId)
            putExtra("trackKey", trackKey)
            putExtra("songName", title)
            putExtra("artist", artist)
            putExtra("duration", validDuration(durationSeconds))
            putExtra("trackGeneration", generation)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            SystemUiBroadcastSender.submit(
                context = context,
                payloadIntent = intent,
                logTag = TAG,
                source = SOURCE_METROLIST
            )
        }.onFailure { error ->
            if (SystemUiBroadcastSender.shouldReportFailure(error)) {
                Log.d(TAG, "trackChanged send failed: ${error.message}")
            }
        }
        return generation
    }

    fun send(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        lyric: String,
        rawLyric: String,
        trackGeneration: Long
    ) {
        if (songId.isEmpty() || rawLyric.isEmpty() || trackGeneration <= 0L) return

        val sanitizedRaw = sanitizeExtendedLrc(rawLyric)
        if (!containsTimedLrc(sanitizedRaw) && !containsTimedLrc(lyric)) {
            Log.d(TAG, "Skip non-timed lyrics for $title")
            return
        }

        val requestId = "metrolist:$songId:$trackGeneration:" +
            Integer.toHexString(sanitizedRaw.hashCode())
        val payloadKey = "$SOURCE_METROLIST:$trackGeneration:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_METROLIST)
            putExtra("eventType", "lyricReady")
            putExtra("requestId", requestId)
            putExtra("mediaId", songId)
            putExtra("trackKey", TrackKeyBuilder.build(title, artist))
            putExtra("songName", title)
            putExtra("artist", artist)
            putExtra("duration", validDuration(durationSeconds))
            putExtra("lyric", lyric)
            putExtra("rawLyric", sanitizedRaw)
            putExtra("translationLyric", "")
            putExtra("trackGeneration", trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            SystemUiBroadcastSender.submitWithLyricLineFallback(
                context = context,
                payloadIntent = intent,
                originalLyric = lyric,
                originalRawLyric = sanitizedRaw,
                originalTranslationLyric = "",
                logTag = TAG,
                source = SOURCE_METROLIST
            )
        }.onSuccess {
            val inlineTags = INLINE_WORD_TIME_REGEX.findAll(sanitizedRaw).count()
            val timing = if (inlineTags > 0) "word" else "line"
            Log.i(
                TAG,
                "Sent Metrolist lyrics: $title (${sanitizedRaw.length} chars, " +
                    "timing=$timing, inlineTags=$inlineTags)"
            )
        }.onFailure { error ->
            payloadGate.forget(payloadKey)
            if (SystemUiBroadcastSender.shouldReportFailure(error)) {
                Log.w(TAG, "Failed to send Metrolist lyrics: $title", error)
            }
        }
    }

    fun sendPlaybackState(
        context: Context,
        state: PlaybackState,
        songId: String,
        title: String,
        artist: String,
        durationMillis: Long,
        trackGeneration: Long,
        force: Boolean = false
    ) {
        if (songId.isEmpty() || trackGeneration <= 0L) return
        if (state.state == PlaybackState.STATE_BUFFERING && state.position <= 0L) return
        if (!playbackStateGate.shouldSend(
                state = state.state,
                position = state.position,
                speed = state.playbackSpeed,
                lastPositionUpdateTime = state.lastPositionUpdateTime,
                moving = isPlaybackInMotion(state.state),
                generation = trackGeneration,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                force = force
            )) {
            return
        }

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_METROLIST)
            putExtra("eventType", "playbackState")
            putExtra("mediaId", songId)
            putExtra("trackKey", TrackKeyBuilder.build(title, artist))
            putExtra("songName", title)
            putExtra("artist", artist)
            putExtra("duration", validDurationMillis(durationMillis))
            putExtra("trackGeneration", trackGeneration)
            putExtra(EXTRA_PLAYBACK_STATE, state.state)
            putExtra(EXTRA_PLAYBACK_POSITION, state.position)
            putExtra(EXTRA_PLAYBACK_SPEED, state.playbackSpeed)
            putExtra(EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME, state.lastPositionUpdateTime)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            SystemUiBroadcastSender.submit(
                context = context,
                payloadIntent = intent,
                logTag = TAG,
                source = SOURCE_METROLIST
            )
        }.onFailure { error ->
            playbackStateGate.reset()
            if (SystemUiBroadcastSender.shouldReportFailure(error)) {
                Log.w(TAG, "Failed to send Metrolist playback state", error)
            }
        }
    }

    private fun Intent.putBridgeDeclaration() {
        putExtra("capabilities", BRIDGE_CAPABILITIES)
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
    }

    private fun isPlaybackInMotion(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    internal fun sanitizeExtendedLrc(lyrics: String): String {
        val result = StringBuilder(lyrics.length)
        for (line in lyrics.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("<") && !TIMED_LRC_REGEX.containsMatchIn(trimmed)) continue
            val bracketPos = trimmed.indexOf('[')
            if (bracketPos >= 0) {
                val closing = trimmed.indexOf(']', bracketPos)
                if (closing >= 0) {
                    val tag = trimmed.substring(bracketPos, closing + 1)
                    val rest = trimmed.substring(closing + 1).trim()
                        .replace(Regex("""\{agent:\w+\}"""), "")
                        .replace(Regex("""\{bg\}"""), "")
                        .trim()
                    if (rest.isNotEmpty()) {
                        result.append(tag).append(rest).append('\n')
                    }
                    continue
                }
            }
            result.append(trimmed).append('\n')
        }
        return result.toString().trim()
    }

    private fun containsTimedLrc(value: String): Boolean = TIMED_LRC_REGEX.containsMatchIn(value)

    private fun validDuration(durationSeconds: Int): Long {
        return if (durationSeconds in 1..MAX_DURATION_SECONDS) {
            durationSeconds.toLong() * 1_000L
        } else {
            0L
        }
    }

    private fun validDurationMillis(durationMillis: Long): Long {
        return if (durationMillis in 1L..(MAX_DURATION_SECONDS * 1_000L)) {
            durationMillis
        } else {
            0L
        }
    }
}
