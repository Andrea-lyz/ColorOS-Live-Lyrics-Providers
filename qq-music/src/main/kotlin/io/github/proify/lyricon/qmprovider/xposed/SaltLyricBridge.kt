/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.proify.extensions.android.BridgeBroadcastSender
import io.github.proify.extensions.bridge.BridgeInlineSegmentPolicy
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.extensions.bridge.retainBridgeLyricLines
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_SaltBridge"
    private const val ACTION_EXTERNAL_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_CAPTURED"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val BRIDGE_PROTOCOL_VERSION = 2
    private const val SOURCE_QQ_MUSIC = "lyricprovider/qq-music"
    private const val BRIDGE_CAPABILITIES =
        "playbackState,trackGeneration,translationToggle"
    private const val BRIDGE_MATCH_POLICY = "mediaId,trackKey,titleArtist"
    private const val EXTRA_PLAYBACK_STATE = "playbackState"
    private const val EXTRA_PLAYBACK_POSITION = "playbackPosition"
    private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
    private const val EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME = "playbackLastPositionUpdateTime"
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val payloadGate = BridgePayloadGate()
    private val playbackStateGate = BridgePlaybackStateGate()

    fun sendTrackChanged(
        context: Context?,
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long,
        trackGeneration: Long
    ) {
        if (context == null || mediaId.isNullOrBlank() || trackGeneration <= 0L) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_QQ_MUSIC)
            putExtra("eventType", "trackChanged")
            putExtra("mediaId", mediaId)
            putExtra("trackKey", buildTrackKey(title, artist))
            putExtra("songName", title.orEmpty())
            putExtra("artist", artist.orEmpty())
            putExtra("duration", validDuration(duration))
            putExtra("trackGeneration", trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            BridgeBroadcastSender.send(context, intent, TAG, SOURCE_QQ_MUSIC)
        }.onSuccess {
            debug("Sent QQ track change, generation=$trackGeneration, id=$mediaId")
        }.onFailure { e ->
            if (!BridgeBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(TAG, "Failed to send QQ track change, generation=$trackGeneration", e)
        }
    }

    fun send(context: Context?, song: Song?, trackGeneration: Long) {
        if (context == null || song == null || trackGeneration <= 0L) return

        val lyricLines = filteredLyricLines(song)
        val rawLyric = toEnhancedLrc(song, lyricLines)
        if (!containsTimedLrc(rawLyric)) {
            debug("Skip bridge payload without timed lyric, id=${song.id.orEmpty()}")
            return
        }

        val lyric = toPlainLrc(song, lyricLines)
        val translationLyric = toTranslationLrc(song, lyricLines)
        val requestId = buildRequestId(song, rawLyric, translationLyric)
        val payloadKey = "$SOURCE_QQ_MUSIC:$trackGeneration:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return
        val trackKey = buildTrackKey(song.name, song.artist)

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_QQ_MUSIC)
            putExtra("eventType", "lyricReady")
            putExtra("requestId", requestId)
            putExtra("mediaId", song.id.orEmpty())
            putExtra("trackKey", trackKey)
            putExtra("songName", song.name.orEmpty())
            putExtra("artist", song.artist.orEmpty())
            putExtra("duration", validDuration(song.duration))
            putExtra("lyric", lyric)
            putExtra("rawLyric", rawLyric)
            putExtra("translationLyric", translationLyric)
            putExtra("trackGeneration", trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            BridgeBroadcastSender.send(context, intent, TAG, SOURCE_QQ_MUSIC)
        }.onSuccess {
            debug(
                "Sent QQ bridge payload, generation=$trackGeneration, " +
                    "id=${song.id.orEmpty()}, " +
                    "lines=${lyricLines.size}/${song.lyrics?.size ?: 0}, " +
                    "rawChars=${rawLyric.length}, transChars=${translationLyric.length}, " +
                    "first=${shortenForLog(lyricLines.firstOrNull()?.text.orEmpty())}"
            )
        }.onFailure { e ->
            payloadGate.forget(payloadKey)
            if (!BridgeBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(TAG, "Failed to send QQ bridge payload, id=${song.id.orEmpty()}", e)
        }
    }

    fun sendPlaybackState(
        context: Context?,
        state: PlaybackState?,
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long,
        trackGeneration: Long,
        force: Boolean = false
    ) {
        if (context == null || state == null || mediaId.isNullOrBlank() || trackGeneration <= 0L) {
            return
        }
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
            )
        ) {
            return
        }

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_QQ_MUSIC)
            putExtra("eventType", "playbackState")
            putExtra("mediaId", mediaId)
            putExtra("trackKey", buildTrackKey(title, artist))
            putExtra("songName", title.orEmpty())
            putExtra("artist", artist.orEmpty())
            putExtra("duration", validDuration(duration))
            putExtra("trackGeneration", trackGeneration)
            putExtra(EXTRA_PLAYBACK_STATE, state.state)
            putExtra(EXTRA_PLAYBACK_POSITION, state.position)
            putExtra(EXTRA_PLAYBACK_SPEED, state.playbackSpeed)
            putExtra(EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME, state.lastPositionUpdateTime)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            BridgeBroadcastSender.send(context, intent, TAG, SOURCE_QQ_MUSIC)
        }.onFailure { e ->
            playbackStateGate.reset()
            if (!BridgeBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(
                TAG,
                "Failed to send QQ playback state, generation=$trackGeneration, " +
                    "state=${state.state}",
                e
            )
        }
    }

    private fun isPlaybackInMotion(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private fun Intent.putBridgeDeclaration(context: Context) {
        putExtra("protocolVersion", BRIDGE_PROTOCOL_VERSION)
        putExtra("playerPackage", context.packageName)
        putExtra("capabilities", BRIDGE_CAPABILITIES)
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
    }

    private fun toEnhancedLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        lines.forEach { line ->
            val words = timedWordsInTextOrder(line)

            if (words.isEmpty()) {
                appendTimedLine(builder, line.begin, line.text.orEmpty())
                return@forEach
            }

            builder.append('[')
                .append(formatLrcTime(line.begin))
                .append(']')
            words.forEach wordLoop@{ word ->
                val segment = cleanInlineSegment(word.text)
                if (BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, segment)) {
                    return@wordLoop
                }
                builder.append('<')
                    .append(formatLrcTime(word.begin))
                    .append('>')
                    .append(segment)
            }
            val end = inferEnhancedLineEnd(line, words)
            if (end > line.begin) {
                builder.append('<')
                    .append(formatLrcTime(end))
                    .append('>')
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun inferEnhancedLineEnd(line: RichLyricLine, words: List<TimedWord>): Long {
        val declaredSpan = max(line.duration, line.end - line.begin)
        if (declaredSpan in 360L..12_000L) {
            return line.begin + declaredSpan
        }
        val lastWordBegin = words.maxOfOrNull { it.begin } ?: line.begin
        return max(line.begin + 720L, lastWordBegin + 520L)
    }

    private data class TimedWord(
        val text: String,
        val begin: Long
    )

    private fun timedWordsInTextOrder(line: RichLyricLine): List<TimedWord> {
        val words = line.words.orEmpty()
            .filter { !it.text.isNullOrEmpty() }
            .map { word ->
                TimedWord(
                    text = word.text.orEmpty(),
                    begin = normalizeWordTime(line, word.begin)
                )
            }
        if (words.isEmpty()) return emptyList()
        val orderedWords = if (hasMeaningfulTimeInversion(words)) {
            synthesizeWordTimes(line, words)
        } else {
            words
        }
        return clampEarlyLineWordPace(line, orderedWords)
    }

    private fun hasMeaningfulTimeInversion(words: List<TimedWord>): Boolean {
        var previous = Long.MIN_VALUE
        words.forEach { word ->
            if (word.begin + 80L < previous) {
                return true
            }
            previous = max(previous, word.begin)
        }
        return false
    }

    private fun synthesizeWordTimes(line: RichLyricLine, words: List<TimedWord>): List<TimedWord> {
        val count = max(words.size, 1)
        val declaredSpan = max(line.duration, line.end - line.begin)
        val fallbackSpan = max(count * 220L, 720L)
        val span = when {
            declaredSpan in 360L..12_000L -> declaredSpan
            else -> fallbackSpan
        }
        val step = max(80L, span / count)
        return words.mapIndexed { index, word ->
            word.copy(begin = line.begin + step * index)
        }
    }

    private fun clampEarlyLineWordPace(
        line: RichLyricLine,
        words: List<TimedWord>
    ): List<TimedWord> {
        if (line.begin > 6_000L || words.size <= 1) return words

        val firstBegin = words.first().begin
        val lastBegin = words.last().begin
        val originalSpan = lastBegin - firstBegin
        val targetSpan = maxDisplayWordSpan(words)
        if (originalSpan <= targetSpan) return words

        val count = max(words.size, 1)
        val step = max(80L, targetSpan / count)
        return words.mapIndexed { index, word ->
            word.copy(begin = line.begin + step * index)
        }
    }

    private fun maxDisplayWordSpan(words: List<TimedWord>): Long {
        val count = max(words.size, 1)
        val hasLatin = words.any { word ->
            word.text.any { it in 'A'..'Z' || it in 'a'..'z' }
        }
        val perWord = if (hasLatin) 360L else 220L
        val hardCap = if (hasLatin) 4_200L else 3_200L
        return max(900L, minOf(hardCap, count * perWord))
    }

    private fun toPlainLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        lines.forEach { appendTimedLine(builder, it.begin, it.text.orEmpty()) }
        return builder.toString()
    }

    private fun toTranslationLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        lines
            .filter { !it.translation.isNullOrBlank() && it.translation?.trim() != "//" }
            .forEach { appendTimedLine(builder, it.begin, it.translation.orEmpty()) }
        return builder.toString()
    }

    private fun filteredLyricLines(song: Song): List<RichLyricLine> {
        return retainBridgeLyricLines(song.lyrics)
    }

    private fun shortenForLog(value: String): String {
        val clean = cleanPlainText(value)
        return if (clean.length <= 48) clean else clean.substring(0, 45) + "..."
    }

    private fun appendMetadata(builder: StringBuilder, song: Song) {
        val title = cleanPlainText(song.name.orEmpty())
        val artist = cleanPlainText(song.artist.orEmpty())
        if (title.isNotBlank()) {
            builder.append("[ti:").append(title).append("]\n")
        }
        if (artist.isNotBlank()) {
            builder.append("[ar:").append(artist).append("]\n")
        }
    }

    private fun appendTimedLine(builder: StringBuilder, timeMillis: Long, text: String) {
        val clean = cleanPlainText(text)
        if (clean.isBlank()) return
        builder.append('[')
            .append(formatLrcTime(timeMillis))
            .append(']')
            .append(clean)
            .append('\n')
    }

    private fun inferLineEnd(line: RichLyricLine): Long {
        var end = max(line.end, line.begin + max(line.duration, 0L))
        line.words.orEmpty().forEach { word ->
            val begin = normalizeWordTime(line, word.begin)
            val wordEnd = normalizeWordTime(line, word.end)
            end = max(end, max(wordEnd, begin + max(word.duration, 0L)))
        }
        return if (end > line.begin) end else line.begin + 3000L
    }

    private fun normalizeWordTime(line: RichLyricLine, wordTime: Long): Long {
        val lineDuration = max(line.duration, line.end - line.begin)
        if (line.begin > 0L &&
            wordTime >= 0L &&
            wordTime <= max(lineDuration + 2000L, 2000L)
        ) {
            return line.begin + wordTime
        }
        return wordTime
    }

    private fun buildRequestId(song: Song, rawLyric: String, translationLyric: String): String {
        val id = song.id.orEmpty().ifBlank { buildTrackKey(song.name, song.artist) }
        val hash = Integer.toHexString((rawLyric + '\n' + translationLyric).hashCode())
        return "qq-music:$id:$hash"
    }

    private fun buildTrackKey(title: String?, artist: String?): String {
        val normalizedTitle = normalizeTrackComponent(title)
        if (normalizedTitle.isBlank()) return ""
        return normalizedTitle + "|" + normalizeTrackComponent(artist)
    }

    private fun validDuration(duration: Long): Long {
        return if (duration in 1L..MAX_REASONABLE_DURATION_MS) duration else 0L
    }

    private fun normalizeTrackComponent(value: String?): String {
        if (value == null) return ""
        val builder = StringBuilder(value.length)
        var inWhitespace = false
        value.trim().forEach { raw ->
            val ch = when (raw) {
                '\u2018', '\u2019', '\u02bc', '\uff07' -> '\''
                else -> raw.lowercaseChar()
            }
            val whitespace = ch == ' ' || ch == '\t'
            if (whitespace) {
                if (!inWhitespace) builder.append(' ')
            } else {
                builder.append(ch)
            }
            inWhitespace = whitespace
        }
        return builder.toString().lowercase(Locale.ROOT)
    }

    private fun cleanInlineSegment(text: String): String {
        return text.replace('\r', ' ').replace('\n', ' ')
    }

    private fun cleanPlainText(text: String): String {
        return text.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun containsTimedLrc(value: String): Boolean {
        return TIMED_LRC_REGEX.containsMatchIn(value)
    }

    private fun debug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }

    private fun formatLrcTime(timeMillis: Long): String {
        val safeTime = max(0L, timeMillis)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        val millis = safeTime % 1000L
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
