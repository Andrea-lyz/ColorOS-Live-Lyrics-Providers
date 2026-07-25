/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.proify.extensions.android.SystemUiBroadcastSender
import io.github.proify.extensions.bridge.BridgeInlineSegmentPolicy
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.extensions.bridge.LrcTimeFormatter
import io.github.proify.extensions.bridge.TrackKeyBuilder
import io.github.proify.extensions.bridge.WordTimeNormalizer
import io.github.proify.extensions.bridge.retainBridgeLyricLines
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_NeteaseBridge"
    private const val SOURCE_NETEASE_MUSIC = "lyricprovider/netease-cloud-music"
    private const val BRIDGE_CAPABILITIES =
        "playbackState,trackGeneration,translationToggle"
    private const val BRIDGE_MATCH_POLICY = "mediaId,trackKey,titleArtist"
    private const val EXTRA_PLAYBACK_STATE = "playbackState"
    private const val EXTRA_PLAYBACK_POSITION = "playbackPosition"
    private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
    private const val EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME = "playbackLastPositionUpdateTime"
    private const val LYRIC_MODE_TRANSLATION = 0
    private const val LYRIC_MODE_ROMA = 1
    private const val MIN_SYNTHETIC_WORD_STEP_MS = 120L
    private const val MAX_SYNTHETIC_WORD_STEP_MS = 620L
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L
    private val payloadGate = BridgePayloadGate()
    private val playbackStateGate = BridgePlaybackStateGate()
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun sendTrackChanged(
        context: Context?,
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long,
        trackGeneration: Long
    ) {
        if (context == null || mediaId.isNullOrBlank() || trackGeneration <= 0L) return

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_NETEASE_MUSIC)
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
            SystemUiBroadcastSender.submit(context, intent, TAG, SOURCE_NETEASE_MUSIC)
        }.onSuccess {
            debug("Sent Netease track change, generation=$trackGeneration, id=$mediaId")
        }.onFailure { e ->
            if (!SystemUiBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(TAG, "Failed to send Netease track change, generation=$trackGeneration", e)
        }
    }

    fun send(
        context: Context?,
        song: Song?,
        lyricMode: Int = -1,
        trackGeneration: Long
    ) {
        if (context == null || song == null || trackGeneration <= 0L) return

        val lyricLines = filteredLyricLines(song)
        val lyric = toPlainLrc(song, lyricLines)
        if (!containsTimedLrc(lyric)) {
            debug("Skip direct lyric payload without timed lyric, id=${song.id.orEmpty()}")
            return
        }

        val rawLyric = if (shouldSendEnhancedRawLyric(lyricMode)) {
            toEnhancedLrc(song, lyricLines)
        } else {
            lyric
        }
        val translationLyric = toTranslationLrc(song, lyricLines)
        val requestId = buildRequestId(song, rawLyric, translationLyric)
        val payloadKey = "$SOURCE_NETEASE_MUSIC:$trackGeneration:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return
        val trackKey = buildTrackKey(song.name, song.artist)

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_NETEASE_MUSIC)
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
            SystemUiBroadcastSender.submitWithLyricLineFallback(
                context = context,
                payloadIntent = intent,
                originalLyric = lyric,
                originalRawLyric = rawLyric,
                originalTranslationLyric = translationLyric,
                logTag = TAG,
                source = SOURCE_NETEASE_MUSIC
            )
        }.onSuccess {
            debug(
                "Sent Netease direct lyric payload, generation=$trackGeneration, " +
                    "id=${song.id.orEmpty()}, " +
                    "lines=${lyricLines.size}/${song.lyrics?.size ?: 0}, " +
                    "mode=$lyricMode, rawMode=${if (rawLyric == lyric) "plain" else "enhanced"}, " +
                    "rawChars=${rawLyric.length}, transChars=${translationLyric.length}, " +
                    "first=${shortenForLog(lyricLines.firstOrNull()?.text.orEmpty())}"
            )
        }.onFailure { e ->
            payloadGate.forget(payloadKey)
            if (!SystemUiBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(TAG, "Failed to send Netease direct lyric payload, id=${song.id.orEmpty()}", e)
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

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_NETEASE_MUSIC)
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
            SystemUiBroadcastSender.submit(context, intent, TAG, SOURCE_NETEASE_MUSIC)
        }.onFailure { e ->
            playbackStateGate.reset()
            if (!SystemUiBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(
                TAG,
                "Failed to send Netease playback state, generation=$trackGeneration, " +
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

    private fun Intent.putBridgeDeclaration() {
        putExtra("capabilities", BRIDGE_CAPABILITIES)
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
    }

    private fun shouldSendEnhancedRawLyric(lyricMode: Int): Boolean {
        // rawLyric is the bridge's primary payload.  It must retain source
        // word timing even when the user's translation/romanization toggle is
        // off; that toggle only controls the provider's secondary display.
        return true
    }

    private fun toEnhancedLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        val hasWordTimedSource = lines.any { hasUsableSourceWords(it) }
        lines.forEach { line ->
            val words = timedWordsInTextOrder(line, hasWordTimedSource)

            if (words.isEmpty()) {
                appendTimedLine(builder, line.begin, line.text.orEmpty())
                return@forEach
            }

            builder.append('[')
                .append(LrcTimeFormatter.format(line.begin))
                .append(']')
            words.forEach wordLoop@{ word ->
                val segment = cleanInlineSegment(word.text)
                if (BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, segment)) {
                    return@wordLoop
                }
                builder.append('<')
                    .append(LrcTimeFormatter.format(word.begin))
                    .append('>')
                    .append(segment)
            }
            val end = inferEnhancedLineEnd(line, words)
            if (end > line.begin) {
                builder.append('<')
                    .append(LrcTimeFormatter.format(end))
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

    private fun timedWordsInTextOrder(
        line: RichLyricLine,
        synthesizeMissingWordTiming: Boolean
    ): List<TimedWord> {
        val words = line.words.orEmpty()
            .filter { !it.text.isNullOrEmpty() }
            .map { word ->
                TimedWord(
                    text = word.text.orEmpty(),
                    begin = normalizeWordTime(line, word.begin)
                )
            }
        if (words.isEmpty()) {
            return if (synthesizeMissingWordTiming) {
                synthesizeWordsFromLineText(line)
            } else {
                emptyList()
            }
        }
        if (synthesizeMissingWordTiming && shouldSplitSingleWholeLineWord(line, words)) {
            val synthesized = synthesizeWordsFromLineText(line)
            if (synthesized.isNotEmpty()) return synthesized
        }
        val orderedWords = if (hasUnusableWordTiming(line, words)) {
            synthesizeWordTimes(line, words)
        } else {
            words
        }
        return clampEarlyLineWordPace(line, orderedWords)
    }

    private fun hasUsableSourceWords(line: RichLyricLine): Boolean {
        return line.words.orEmpty().any { !it.text.isNullOrEmpty() }
    }

    private fun hasUnusableWordTiming(line: RichLyricLine, words: List<TimedWord>): Boolean {
        if (words.size <= 1) return false
        var previous = Long.MIN_VALUE
        words.forEach { word ->
            if (word.begin <= previous) {
                return true
            }
            previous = max(previous, word.begin)
        }
        val span = words.last().begin - words.first().begin
        val declaredSpan = max(line.duration, line.end - line.begin)
        return declaredSpan >= 1_000L && span < minSyntheticWordSpan(words.size)
    }

    private fun shouldSplitSingleWholeLineWord(
        line: RichLyricLine,
        words: List<TimedWord>
    ): Boolean {
        if (words.size != 1) return false
        val text = cleanPlainText(line.text.orEmpty())
        val wordText = cleanPlainText(words.first().text)
        return text.isNotBlank()
            && text == wordText
            && text.any { it.isWhitespace() }
    }

    private fun synthesizeWordsFromLineText(line: RichLyricLine): List<TimedWord> {
        val tokens = splitSyntheticWordSegments(line.text.orEmpty())
        if (tokens.size <= 1) return emptyList()
        val span = syntheticWordSpan(line, tokens.size)
        val step = syntheticWordStep(span, tokens.size)
        return tokens.mapIndexed { index, token ->
            TimedWord(
                text = token,
                begin = line.begin + step * index
            )
        }
    }

    private fun splitSyntheticWordSegments(text: String): List<String> {
        val clean = cleanPlainText(text)
        if (clean.isBlank()) return emptyList()
        return Regex("""\S+\s*""").findAll(clean)
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun syntheticWordSpan(line: RichLyricLine, wordCount: Int): Long {
        val declaredSpan = max(line.duration, line.end - line.begin)
        val fallbackSpan = max(wordCount * 300L, 720L)
        return when {
            declaredSpan in 360L..12_000L -> declaredSpan
            else -> fallbackSpan
        }
    }

    private fun syntheticWordStep(span: Long, wordCount: Int): Long {
        val count = max(wordCount, 1)
        return minOf(
            MAX_SYNTHETIC_WORD_STEP_MS,
            max(MIN_SYNTHETIC_WORD_STEP_MS, span / count)
        )
    }

    private fun minSyntheticWordSpan(wordCount: Int): Long {
        return max(240L, minOf(900L, wordCount * 80L))
    }

    private fun synthesizeWordTimes(line: RichLyricLine, words: List<TimedWord>): List<TimedWord> {
        val count = max(words.size, 1)
        val declaredSpan = max(line.duration, line.end - line.begin)
        val fallbackSpan = max(count * 220L, 720L)
        val span = when {
            declaredSpan in 360L..12_000L -> declaredSpan
            else -> fallbackSpan
        }
        val step = syntheticWordStep(span, count)
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
            .append(LrcTimeFormatter.format(timeMillis))
            .append(']')
            .append(clean)
            .append('\n')
    }

    private fun normalizeWordTime(line: RichLyricLine, wordTime: Long): Long =
        WordTimeNormalizer.toAbsolute(line, wordTime)

    private fun buildRequestId(song: Song, rawLyric: String, translationLyric: String): String {
        val id = song.id.orEmpty().ifBlank { buildTrackKey(song.name, song.artist) }
        val hash = Integer.toHexString((rawLyric + '\n' + translationLyric).hashCode())
        return "netease-cloud-music:$id:$hash"
    }

    private fun buildTrackKey(title: String?, artist: String?): String =
        TrackKeyBuilder.build(title, artist)

    private fun validDuration(duration: Long): Long {
        return if (duration in 1L..MAX_REASONABLE_DURATION_MS) duration else 0L
    }

    private fun normalizeTrackComponent(value: String?): String =
        TrackKeyBuilder.normalizeTrackComponent(value)

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
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, message)
        }
    }
}
