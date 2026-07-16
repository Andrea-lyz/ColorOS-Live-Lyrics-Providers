/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qishuiprovider.xposed

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.extensions.bridge.retainBridgeLyricLines
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_QiShuiBridge"
    private const val ACTION_EXTERNAL_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_CAPTURED"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val BRIDGE_PROTOCOL_VERSION = 2
    private const val SOURCE_QISHUI = "lyricprovider/qishui-music"
    private const val BRIDGE_CAPABILITIES =
        "playbackState,trackGeneration,translationToggle"
    private const val BRIDGE_MATCH_POLICY = "mediaId,trackKey,titleArtist"
    private const val EXTRA_PLAYBACK_STATE = "playbackState"
    private const val EXTRA_PLAYBACK_POSITION = "playbackPosition"
    private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
    private const val EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME = "playbackLastPositionUpdateTime"
    private const val EARLY_METADATA_WINDOW_MS = 30_000L
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L
    private const val SKIP_TIMED_LYRIC_LOG_THROTTLE_MS = 10_000L
    private val payloadGate = BridgePayloadGate()
    private val playbackStateGate = BridgePlaybackStateGate()
    private var lastSkippedTimedLyricKey = ""
    private var lastSkippedTimedLyricLogAt = 0L

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
            putExtra("source", SOURCE_QISHUI)
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
            context.sendBroadcast(intent)
        }.onSuccess {
            debug("Sent QiShui track change, generation=$trackGeneration, id=$mediaId")
        }.onFailure { e ->
            Log.w(TAG, "Failed to send QiShui track change, generation=$trackGeneration", e)
        }
    }

    private val creditRoleKeywords = arrayOf(
        "lyrics",
        "lyricist",
        "composer",
        "arranger",
        "producer",
        "produced",
        "production",
        "publisher",
        "copyright",
        "op",
        "sp",
        "\u4f5c\u8bcd",
        "\u4f5c\u8a5e",
        "\u4f5c\u66f2",
        "\u7f16\u66f2",
        "\u7de8\u66f2",
        "\u5236\u4f5c",
        "\u88fd\u4f5c",
        "\u51fa\u54c1"
    )

    fun send(context: Context?, song: Song?, trackGeneration: Long = 0L) {
        if (context == null || song == null) return

        val lyricLines = filteredLyricLines(song)
        val rawLyric = toEnhancedLrc(song, lyricLines)
        if (!containsTimedLrc(rawLyric)) {
            if (isDiagnosticsEnabled() && shouldLogSkippedTimedLyric(song)) {
                debug("Skip bridge payload without timed lyric, id=${song.id.orEmpty()}")
            }
            return
        }

        val lyric = toPlainLrc(song, lyricLines)
        val translationLyric = toTranslationLrc(song, lyricLines)
        val requestId = buildRequestId(song, rawLyric, translationLyric)
        val payloadKey = "$SOURCE_QISHUI:$trackGeneration:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return
        val trackKey = buildTrackKey(song.name, song.artist)

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_QISHUI)
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
            context.sendBroadcast(intent)
        }.onSuccess {
            debug(
                "Sent QiShui bridge payload, id=${song.id.orEmpty()}, " +
                    "lines=${lyricLines.size}/${song.lyrics?.size ?: 0}, " +
                    "rawChars=${rawLyric.length}, transChars=${translationLyric.length}, " +
                    "first=${shortenForLog(lyricLines.firstOrNull()?.text.orEmpty())}"
            )
        }.onFailure { e ->
            payloadGate.forget(payloadKey)
            Log.w(TAG, "Failed to send QiShui bridge payload, id=${song.id.orEmpty()}", e)
        }
    }

    fun sendPlaybackState(
        context: Context?,
        state: PlaybackState?,
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long,
        trackGeneration: Long
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
                nowElapsedMillis = SystemClock.elapsedRealtime()
            )
        ) {
            return
        }

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", SOURCE_QISHUI)
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
            context.sendBroadcast(intent)
        }.onFailure { e ->
            playbackStateGate.reset()
            Log.w(
                TAG,
                "Failed to send QiShui playback state, generation=$trackGeneration, " +
                    "state=${state.state}",
                e
            )
        }
    }

    private fun isDiagnosticsEnabled(): Boolean = Log.isLoggable(TAG, Log.DEBUG)

    private fun debug(message: String) {
        if (isDiagnosticsEnabled()) Log.d(TAG, message)
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
            words.forEach { word ->
                builder.append('<')
                    .append(formatLrcTime(word.begin))
                    .append('>')
                    .append(cleanInlineSegment(word.text))
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
        val maxWordEnd = words.maxOfOrNull { it.end } ?: line.begin
        val lastWordBegin = words.maxOfOrNull { it.begin } ?: line.begin
        return max(max(line.end, maxWordEnd), max(line.begin + 720L, lastWordBegin + 520L))
    }

    private data class TimedWord(
        val text: String,
        val begin: Long,
        val end: Long
    )

    private fun timedWordsInTextOrder(line: RichLyricLine): List<TimedWord> {
        val words = line.words.orEmpty()
            .filter { !it.text.isNullOrEmpty() }
            .map { word ->
                val begin = normalizeWordTime(line, word.begin)
                val declaredEnd = max(word.end, word.begin + max(word.duration, 0L))
                val end = max(begin, normalizeWordTime(line, declaredEnd))
                TimedWord(
                    text = word.text.orEmpty(),
                    begin = begin,
                    end = end
                )
            }
        if (words.isEmpty()) return emptyList()
        return if (hasMeaningfulTimeInversion(words)) {
            synthesizeWordTimes(line, words)
        } else {
            words
        }
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
            val begin = line.begin + step * index
            word.copy(begin = begin, end = begin + step)
        }
    }

    private fun normalizeWordTime(line: RichLyricLine, wordTime: Long): Long {
        val lineDuration = max(line.duration, line.end - line.begin)
        val likelyAbsolute = wordTime + 250L >= line.begin
        if (!likelyAbsolute &&
            line.begin > 0L &&
            wordTime >= 0L &&
            wordTime <= max(lineDuration + 2_000L, 2_000L)
        ) {
            return line.begin + wordTime
        }
        return max(0L, wordTime)
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
            .filter { cleanPlainText(it.translation.orEmpty()) != cleanPlainText(it.text.orEmpty()) }
            .forEach { appendTimedLine(builder, it.begin, it.translation.orEmpty()) }
        return builder.toString()
    }

    private fun filteredLyricLines(song: Song): List<RichLyricLine> {
        return retainBridgeLyricLines(song.lyrics)
    }

    private fun isLikelyMetadataLine(
        line: RichLyricLine,
        song: Song,
        removedEarlyCredit: Boolean
    ): Boolean {
        val text = cleanPlainText(line.text.orEmpty())
        if (text.isBlank()) return true
        if (line.begin <= EARLY_METADATA_WINDOW_MS) {
            if (looksLikeKnownTrackCredit(text, song)) return true
        }
        if (looksLikeCreditRoleLine(text)) return true
        return removedEarlyCredit &&
            line.begin <= EARLY_METADATA_WINDOW_MS &&
            looksLikeArtistCreditContinuation(text)
    }

    private fun looksLikeKnownTrackCredit(text: String, song: Song): Boolean {
        val value = normalizeCreditIdentity(text)
        if (value.length < 3 || value.length > 96 || endsLikeSentence(text)) return false

        val title = normalizeCreditIdentity(song.name)
        if (title.isNotBlank() && value == title) return true

        val artist = normalizeCreditIdentity(song.artist)
        if (artist.isNotBlank() && value == artist) return true

        return splitArtistParts(song.artist).any { part ->
            part.length >= 3 && (value == part || (value.contains(part) && value.length <= part.length + 12))
        }
    }

    private fun looksLikeCreditRoleLine(text: String): Boolean {
        val value = cleanPlainText(text)
        if (value.length > 160) return false

        val lower = value.lowercase(Locale.ROOT)
        val colon = firstColonIndex(value)
        if (colon > 0 && colon <= 48) {
            val prefix = lower.substring(0, colon).trim()
            if (containsCreditRoleKeyword(prefix)) return true
        }
        return creditRoleKeywords.any { keyword ->
            val lowerKeyword = keyword.lowercase(Locale.ROOT)
            lower == lowerKeyword ||
                lower.startsWith("$lowerKeyword:") ||
                lower.startsWith("$lowerKeyword\uff1a") ||
                lower.startsWith("$lowerKeyword by ") ||
                lower.startsWith("$lowerKeyword ")
        }
    }

    private fun looksLikeArtistCreditContinuation(text: String): Boolean {
        val value = cleanPlainText(text)
        if (value.length < 3 || value.length > 96 || endsLikeSentence(value)) return false
        return (value.indexOf('/') >= 0 ||
            value.indexOf('&') >= 0 ||
            value.indexOf(',') >= 0 ||
            value.indexOf('\u3001') >= 0) &&
            containsLetter(value) &&
            countWhitespaceRuns(value) <= 4
    }

    private fun firstColonIndex(value: String): Int {
        val ascii = value.indexOf(':')
        val fullWidth = value.indexOf('\uff1a')
        if (ascii < 0) return fullWidth
        if (fullWidth < 0) return ascii
        return minOf(ascii, fullWidth)
    }

    private fun containsCreditRoleKeyword(value: String): Boolean {
        return creditRoleKeywords.any { value.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun splitArtistParts(artist: String?): List<String> {
        val value = artist ?: return emptyList()
        return value.split(Regex("[/,&;\\uff0c\\uff1b\\u3001]"))
            .map { normalizeCreditIdentity(it) }
            .filter { it.isNotBlank() }
    }

    private fun normalizeCreditIdentity(value: String?): String {
        val text = cleanPlainText(value.orEmpty()).lowercase(Locale.ROOT)
        val builder = StringBuilder(text.length)
        var inWhitespace = false
        text.forEach { ch ->
            val normalized = when (ch) {
                '\u2018', '\u2019', '\u02bc', '\uff07' -> '\''
                else -> ch
            }
            if (normalized.isLetterOrDigit() || normalized == '-' || normalized == '\'') {
                builder.append(normalized)
                inWhitespace = false
            } else if (normalized.isWhitespace()) {
                if (!inWhitespace && builder.isNotEmpty()) {
                    builder.append(' ')
                }
                inWhitespace = true
            }
        }
        return builder.toString().trim()
    }

    private fun containsLetter(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (Character.isLetter(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun endsLikeSentence(value: String): Boolean {
        if (value.isBlank()) return false
        return when (value.last()) {
            '.', '!', '?', '\u3002', '\uff01', '\uff1f' -> true
            else -> false
        }
    }

    private fun countWhitespaceRuns(value: String): Int {
        var count = 0
        var inWhitespace = false
        value.forEach {
            if (it.isWhitespace()) {
                if (!inWhitespace) {
                    count++
                    inWhitespace = true
                }
            } else {
                inWhitespace = false
            }
        }
        return count
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

    private fun buildRequestId(song: Song, rawLyric: String, translationLyric: String): String {
        val id = song.id.orEmpty().ifBlank { buildTrackKey(song.name, song.artist) }
        val hash = Integer.toHexString((rawLyric + '\n' + translationLyric).hashCode())
        return "qishui-music:$id:$hash"
    }

    private fun shouldLogSkippedTimedLyric(song: Song): Boolean {
        val key = song.id.orEmpty().ifBlank { buildTrackKey(song.name, song.artist) }
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (key == lastSkippedTimedLyricKey &&
                now - lastSkippedTimedLyricLogAt < SKIP_TIMED_LYRIC_LOG_THROTTLE_MS
            ) {
                return false
            }
            lastSkippedTimedLyricKey = key
            lastSkippedTimedLyricLogAt = now
        }
        return true
    }

    private fun buildTrackKey(title: String?, artist: String?): String {
        val normalizedTitle = normalizeTrackComponent(title)
        if (normalizedTitle.isBlank()) return ""
        return normalizedTitle + "|" + normalizeTrackComponent(artist)
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

    private fun validDuration(duration: Long): Long {
        return if (duration in 1L..MAX_REASONABLE_DURATION_MS) duration else 0L
    }

    private fun cleanInlineSegment(text: String): String {
        return text.replace('\r', ' ').replace('\n', ' ')
    }

    private fun cleanPlainText(text: String): String {
        return text.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsTimedLrc(value: String): Boolean {
        return Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
            .containsMatchIn(value)
    }

    private fun formatLrcTime(timeMillis: Long): String {
        val safeTime = max(0L, timeMillis)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        val millis = safeTime % 1000L
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
