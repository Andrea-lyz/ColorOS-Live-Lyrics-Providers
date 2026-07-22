/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.Html
import android.util.Log
import io.github.proify.extensions.android.SystemUiBroadcastSender
import io.github.proify.extensions.bridge.BridgeInlineSegmentPolicy
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_AppleBridge"
    private const val SOURCE_APPLE_MUSIC = "lyricprovider/apple-music"
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

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_APPLE_MUSIC)
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
            submitAppleBroadcast(context, intent)
        }.onSuccess {
            debug("Sent Apple Music track change, generation=$trackGeneration, id=$mediaId")
        }.onFailure { e ->
            if (!SystemUiBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(TAG, "Failed to send Apple Music track change, generation=$trackGeneration", e)
        }
    }

    fun send(context: Context?, song: Song?, trackGeneration: Long) {
        if (context == null || song == null || trackGeneration <= 0L) return

        val lyricLines = filteredLyricLines(song)
        val rawLyric = toEnhancedLrc(song, lyricLines)
        if (!containsTimedLrc(rawLyric)) {
            debug(
                "Apple Music lyric payload not ready, generation=$trackGeneration, " +
                    "id=${song.id.orEmpty()}, lines=${lyricLines.size}"
            )
            return
        }

        val lyric = toPlainLrc(song, lyricLines)
        val translationLyric = toTranslationLrc(song, lyricLines)
        val requestId = buildRequestId(song, rawLyric, translationLyric)
        val payloadKey = "$SOURCE_APPLE_MUSIC:$trackGeneration:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return
        val trackKey = buildTrackKey(song.name, song.artist)

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_APPLE_MUSIC)
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
            submitAppleBroadcast(context, intent)
        }.onSuccess {
            debug(
                "Sent Apple Music lyric payload, generation=$trackGeneration, " +
                    "id=${song.id.orEmpty()}, rawChars=${rawLyric.length}"
            )
        }.onFailure { e ->
            payloadGate.forget(payloadKey)
            if (!SystemUiBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(TAG, "Failed to send Apple Music direct lyric payload, id=${song.id.orEmpty()}", e)
        }
    }

    fun sendPlaybackState(
        context: Context?,
        playbackState: Int,
        playbackPosition: Long,
        playbackSpeed: Float,
        playbackLastPositionUpdateTime: Long,
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long,
        trackGeneration: Long,
        force: Boolean = false
    ) {
        if (context == null || mediaId.isNullOrBlank() || trackGeneration <= 0L) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!playbackStateGate.shouldSend(
                state = playbackState,
                position = playbackPosition,
                speed = playbackSpeed,
                lastPositionUpdateTime = playbackLastPositionUpdateTime,
                moving = isPlaybackInMotion(playbackState),
                generation = trackGeneration,
                nowElapsedMillis = nowElapsed,
                force = force
            )
        ) {
            return
        }

        val intent = Intent().apply {
            putBridgeDeclaration()
            putExtra("source", SOURCE_APPLE_MUSIC)
            putExtra("eventType", "playbackState")
            putExtra("mediaId", mediaId)
            putExtra("trackKey", buildTrackKey(title, artist))
            putExtra("songName", title.orEmpty())
            putExtra("artist", artist.orEmpty())
            putExtra("duration", validDuration(duration))
            putExtra("trackGeneration", trackGeneration)
            putExtra(EXTRA_PLAYBACK_STATE, playbackState)
            putExtra(EXTRA_PLAYBACK_POSITION, playbackPosition)
            putExtra(EXTRA_PLAYBACK_SPEED, playbackSpeed)
            putExtra(EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME, playbackLastPositionUpdateTime)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            submitAppleBroadcast(context, intent)
        }.onFailure { e ->
            playbackStateGate.reset()
            if (!SystemUiBroadcastSender.shouldReportFailure(e)) return@onFailure
            Log.w(
                TAG,
                "Failed to send Apple Music playback state, generation=$trackGeneration, " +
                    "state=$playbackState",
                e
            )
        }
    }

    private fun isPlaybackInMotion(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private fun submitAppleBroadcast(context: Context, payload: Intent) =
        SystemUiBroadcastSender.submit(context, payload, TAG, SOURCE_APPLE_MUSIC)

    private fun Intent.putBridgeDeclaration() {
        putExtra("capabilities", BRIDGE_CAPABILITIES)
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
    }

    private fun toEnhancedLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        prepareLrcLines(lines).forEach { prepared ->
            val line = prepared.line
            val words = prepared.words
            if (words.isEmpty()) {
                appendTimedLine(builder, prepared.begin, line.text.orEmpty())
                return@forEach
            }

            builder.append('[')
                .append(formatLrcTime(prepared.begin))
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
            val end = inferEnhancedLineEnd(line, words, prepared.begin)
            if (end > prepared.begin) {
                builder.append('<')
                    .append(formatLrcTime(end))
                    .append('>')
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun inferEnhancedLineEnd(
        line: RichLyricLine,
        words: List<TimedWord>,
        lineBegin: Long
    ): Long {
        val declaredSpan = max(line.duration, line.end - line.begin)
        val declaredEnd = max(line.end, line.begin + declaredSpan)
        val declaredRemaining = declaredEnd - lineBegin
        if (declaredRemaining in 360L..12_000L) {
            return declaredEnd
        }
        val lastWordBegin = words.maxOfOrNull { it.begin } ?: lineBegin
        return max(lineBegin + 720L, lastWordBegin + 520L)
    }

    private data class PreparedLrcLine(
        val line: RichLyricLine,
        val begin: Long,
        val words: List<TimedWord>
    )

    private data class TimedWord(
        val text: String,
        val begin: Long
    )

    private data class TokenPlacement(
        val word: TimedWord,
        val match: TokenMatch
    )

    private fun prepareLrcLines(lines: List<RichLyricLine>): List<PreparedLrcLine> {
        var previousBegin = -1L
        return lines
            .map { line ->
                val words = timedWordsInTextOrder(line)
                PreparedLrcLine(line, lineStartForLrc(line, words), words)
            }
            .sortedBy { it.begin }
            .map { prepared ->
                val begin = if (prepared.begin <= previousBegin) {
                    previousBegin + 1L
                } else {
                    prepared.begin
                }
                previousBegin = begin
                prepared.copy(begin = begin)
            }
    }

    private fun lineStartForLrc(line: RichLyricLine, words: List<TimedWord>): Long {
        val firstWordBegin = words.firstOrNull()?.begin ?: return line.begin
        if (line.begin <= 0L) return firstWordBegin
        val leadIn = firstWordBegin - line.begin
        return if (leadIn in 0L..2_000L) line.begin else firstWordBegin
    }

    private fun timedWordsInTextOrder(line: RichLyricLine): List<TimedWord> {
        val sourceWords = line.words.orEmpty()
            .filter { !it.text.isNullOrEmpty() }
            .map { word ->
                TimedWord(
                    text = cleanPlainText(word.text.orEmpty()),
                    begin = normalizeWordTime(line, word.begin)
                )
            }
            .filter { it.text.isNotBlank() }
        if (sourceWords.isEmpty()) return emptyList()

        val orderedWords = if (hasMeaningfulTimeInversion(sourceWords)) {
            synthesizeWordTimes(line, sourceWords)
        } else {
            sourceWords
        }
        return mergeContiguousDisplayFragments(line, orderedWords)
    }

    private fun mergeContiguousDisplayFragments(
        line: RichLyricLine,
        words: List<TimedWord>
    ): List<TimedWord> {
        val lineText = cleanPlainText(line.text.orEmpty())
        if (lineText.isBlank()) return words
        if (words.size <= 1) return reconcileDisplayText(line, lineText, words)

        val placements = mutableListOf<TokenPlacement>()
        val usedRanges = mutableListOf<IntRange>()
        words.forEach { word ->
            val token = cleanPlainText(word.text)
            val match = findTokenMatch(lineText, token, usedRanges)
            if (match != null) {
                placements += TokenPlacement(word, match)
                usedRanges += match.start until match.end
            }
        }

        if (placements.isEmpty()) return reconcileDisplayText(line, lineText, words)
        if (hasPoorDisplayTextCoverage(lineText, placements)) {
            return synthesizeDisplayTextWords(line, lineText)
        }
        if (placements.size < words.size && placements.size <= 1) {
            return synthesizeDisplayTextWords(line, lineText)
        }

        var searchIndex = 0
        val matched = mutableListOf<TimedWord>()
        placements.sortedBy { it.match.start }.forEach { placement ->
            val word = placement.word
            val match = placement.match
            val prefix = lineText.substring(searchIndex, match.start)
            appendDisplayFragment(matched, word, prefix, match.text)
            searchIndex = match.end
        }

        val suffix = lineText.substring(searchIndex)
        if (suffix.isNotEmpty() && matched.isNotEmpty()) {
            val lastIndex = matched.lastIndex
            matched[lastIndex] = matched[lastIndex].copy(
                text = matched[lastIndex].text + suffix
            )
        }
        return reconcileDisplayText(line, lineText, matched)
    }

    private fun reconcileDisplayText(
        line: RichLyricLine,
        lineText: String,
        words: List<TimedWord>
    ): List<TimedWord> {
        return if (appleBridgeDisplayTextMismatch(lineText, words.map { it.text })) {
            synthesizeDisplayTextWords(line, lineText)
        } else {
            words
        }
    }

    private fun hasPoorDisplayTextCoverage(
        lineText: String,
        placements: List<TokenPlacement>
    ): Boolean {
        val full = comparableLyricText(lineText)
        if (full.length <= 4) return false
        val matched = comparableLyricText(placements.joinToString("") { it.match.text })
        return matched.length * 2 < full.length
    }

    private fun comparableLyricText(value: String): String {
        return value.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
    }

    private fun synthesizeDisplayTextWords(
        line: RichLyricLine,
        lineText: String
    ): List<TimedWord> {
        val segments = splitDisplayTextForSyntheticTiming(lineText)
        if (segments.isEmpty()) return emptyList()

        val declaredSpan = max(line.duration, line.end - line.begin)
        val span = when {
            declaredSpan in 360L..12_000L -> declaredSpan
            else -> max(segments.size * 220L, 720L)
        }
        val step = max(80L, span / max(segments.size, 1))
        val start = if (line.begin > 0L) line.begin else 0L
        return segments.mapIndexed { index, segment ->
            TimedWord(segment, start + step * index)
        }
    }

    private fun splitDisplayTextForSyntheticTiming(lineText: String): List<String> {
        val segments = mutableListOf<String>()
        val pending = StringBuilder()
        var index = 0
        while (index < lineText.length) {
            val ch = lineText[index]
            when {
                ch.isWhitespace() -> {
                    if (segments.isNotEmpty()) {
                        segments[segments.lastIndex] = segments.last() + ch
                    } else {
                        pending.append(ch)
                    }
                    index++
                }
                isAsciiWordLike(ch) || ch == '\'' || ch == '\u2019' -> {
                    val start = index
                    index++
                    while (index < lineText.length) {
                        val next = lineText[index]
                        if (!isAsciiWordLike(next) && next != '\'' && next != '\u2019') break
                        index++
                    }
                    segments += pending.append(lineText.substring(start, index)).toString()
                    pending.clear()
                }
                ch.isLetterOrDigit() -> {
                    segments += pending.append(ch).toString()
                    pending.clear()
                    index++
                }
                else -> {
                    if (segments.isNotEmpty()) {
                        segments[segments.lastIndex] = segments.last() + ch
                    } else {
                        pending.append(ch)
                    }
                    index++
                }
            }
        }
        if (pending.isNotEmpty()) {
            if (segments.isNotEmpty()) {
                segments[segments.lastIndex] = segments.last() + pending.toString()
            } else {
                segments += pending.toString()
            }
        }
        return segments.map { it.trimStart() }.filter { it.isNotBlank() }
    }

    private fun appendDisplayFragment(
        matched: MutableList<TimedWord>,
        word: TimedWord,
        prefix: String,
        text: String
    ) {
        val mergeWithPrevious = matched.isNotEmpty() &&
                prefix.none { it.isWhitespace() } &&
                containsLatinLetter(matched.last().text + text)

        if (mergeWithPrevious) {
            val lastIndex = matched.lastIndex
            matched[lastIndex] = matched[lastIndex].copy(
                text = matched[lastIndex].text + prefix + text
            )
            return
        }

        if (prefix.isNotEmpty() && matched.isNotEmpty()) {
            val lastIndex = matched.lastIndex
            matched[lastIndex] = matched[lastIndex].copy(
                text = matched[lastIndex].text + prefix
            )
        }
        matched += word.copy(text = if (matched.isEmpty()) prefix + text else text)
    }

    private data class TokenMatch(
        val start: Int,
        val end: Int,
        val text: String
    )

    private fun findTokenMatch(
        lineText: String,
        token: String,
        usedRanges: List<IntRange>
    ): TokenMatch? {
        if (token.isBlank()) return null
        findTokenIndex(lineText, token, usedRanges)?.let { index ->
            return TokenMatch(index, index + token.length, lineText.substring(index, index + token.length))
        }

        val compact = token.filterNot { it.isWhitespace() }
        if (compact.length != token.length && containsLatinLetter(compact)) {
            findTokenIndex(lineText, compact, usedRanges)?.let { index ->
                return TokenMatch(
                    index,
                    index + compact.length,
                    lineText.substring(index, index + compact.length)
                )
            }
        }
        return null
    }

    private fun findTokenIndex(
        lineText: String,
        token: String,
        usedRanges: List<IntRange>
    ): Int? {
        findTokenIndex(lineText, token, usedRanges, ignoreCase = false)?.let { return it }
        return findTokenIndex(lineText, token, usedRanges, ignoreCase = true)
    }

    private fun findTokenIndex(
        lineText: String,
        token: String,
        usedRanges: List<IntRange>,
        ignoreCase: Boolean
    ): Int? {
        var startIndex = 0
        while (startIndex < lineText.length) {
            val index = lineText.indexOf(token, startIndex, ignoreCase = ignoreCase)
            if (index < 0) return null
            val end = index + token.length
            if (usedRanges.none { range -> index < range.last + 1 && end > range.first }) {
                return index
            }
            startIndex = index + 1
        }
        return null
    }

    private fun containsLatinLetter(value: String): Boolean {
        return value.any { it in 'A'..'Z' || it in 'a'..'z' }
    }

    private fun isAsciiWordLike(value: Char): Boolean {
        return value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9'
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

    private fun toPlainLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        prepareLrcLines(lines).forEach {
            appendTimedLine(builder, it.begin, it.line.text.orEmpty())
        }
        return builder.toString()
    }

    private fun toTranslationLrc(song: Song, lines: List<RichLyricLine>): String {
        val builder = StringBuilder()
        appendMetadata(builder, song)
        prepareLrcLines(lines)
            .filter {
                !it.line.translation.isNullOrBlank() && it.line.translation?.trim() != "//"
            }
            .forEach { appendTimedLine(builder, it.begin, it.line.translation.orEmpty()) }
        return builder.toString()
    }

    private fun filteredLyricLines(song: Song): List<RichLyricLine> {
        return song.lyrics.orEmpty()
            .filter { !it.text.isNullOrBlank() }
            .sortedBy { it.begin }
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

    private fun normalizeWordTime(line: RichLyricLine, wordTime: Long): Long {
        val lineDuration = max(line.duration, line.end - line.begin)
        if (line.begin > 0L &&
            wordTime >= 0L &&
            wordTime + 250L < line.begin &&
            wordTime <= max(lineDuration + 2000L, 2000L)
        ) {
            return line.begin + wordTime
        }
        return wordTime
    }

    private fun buildRequestId(song: Song, rawLyric: String, translationLyric: String): String {
        val id = song.id.orEmpty().ifBlank { buildTrackKey(song.name, song.artist) }
        val hash = Integer.toHexString((rawLyric + '\n' + translationLyric).hashCode())
        return "apple-music:$id:$hash"
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
        return stripHtml(text)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun stripHtml(text: String): String {
        if (text.indexOf('<') < 0 && text.indexOf('&') < 0) return text
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun containsTimedLrc(value: String): Boolean {
        return TIMED_LRC_REGEX.containsMatchIn(value)
    }

    private fun debug(message: String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
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

    private fun shortenForLog(value: String): String {
        val clean = cleanPlainText(value)
        return if (clean.length <= 48) clean else clean.substring(0, 45) + "..."
    }
}

internal fun appleBridgeDisplayTextMismatch(
    lineText: String,
    wordTexts: List<String>
): Boolean {
    fun normalize(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    val expected = normalize(lineText)
    if (expected.isEmpty()) return false
    val actual = normalize(wordTexts.joinToString(""))
    return expected != actual
}
