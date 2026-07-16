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
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.extensions.bridge.retainBridgeLyricLines
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_NeteaseBridge"
    private const val ACTION_EXTERNAL_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_CAPTURED"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val BRIDGE_PROTOCOL_VERSION = 2
    private const val SOURCE_NETEASE_MUSIC = "lyricprovider/netease-cloud-music"
    private const val BRIDGE_CAPABILITIES =
        "playbackState,trackGeneration,translationToggle"
    private const val BRIDGE_MATCH_POLICY = "mediaId,trackKey,titleArtist"
    private const val EXTRA_PLAYBACK_STATE = "playbackState"
    private const val EXTRA_PLAYBACK_POSITION = "playbackPosition"
    private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
    private const val EXTRA_PLAYBACK_LAST_POSITION_UPDATE_TIME = "playbackLastPositionUpdateTime"
    private const val EARLY_METADATA_WINDOW_MS = 30_000L
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

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
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
            context.sendBroadcast(intent)
        }.onSuccess {
            debug("Sent Netease track change, generation=$trackGeneration, id=$mediaId")
        }.onFailure { e ->
            Log.w(TAG, "Failed to send Netease track change, generation=$trackGeneration", e)
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
        "editor",
        "editing",
        "engineer",
        "engineered",
        "audio",
        "sound",
        "musician",
        "musicians",
        "performer",
        "performed",
        "performance",
        "vocal",
        "vocals",
        "cello",
        "viola",
        "violin",
        "orchestra",
        "band",
        "choir",
        "conductor",
        "accordion",
        "strings",
        "guitar",
        "bass",
        "drums",
        "piano",
        "mix",
        "mixed",
        "master",
        "mastered",
        "recording",
        "recorded",
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
        "\u51fa\u54c1",
        "\u7f16\u8f91",
        "\u7de8\u8f2f",
        "\u97f3\u9891",
        "\u97f3\u983b",
        "\u5de5\u7a0b",
        "\u5de5\u7a0b\u5e08",
        "\u5de5\u7a0b\u5e2b",
        "\u6f14\u594f",
        "\u4e50\u624b",
        "\u6a02\u624b",
        "\u76d1\u5236",
        "\u76e3\u88fd",
        "\u6f14\u5531",
        "\u6b4c\u624b",
        "\u539f\u5531",
        "\u7ffb\u5531",
        "\u6df7\u97f3",
        "\u6bcd\u5e26",
        "\u6bcd\u5e36",
        "\u5f55\u97f3",
        "\u9304\u97f3",
        "\u548c\u58f0",
        "\u548c\u8072",
        "\u548c\u97f3",
        "\u4eba\u58f0",
        "\u4eba\u8072",
        "\u4e50\u961f",
        "\u6a02\u968a",
        "\u7ba1\u5f26\u4e50",
        "\u7ba1\u5f26\u6a02",
        "\u4ea4\u54cd\u4e50\u56e2",
        "\u4ea4\u97ff\u6a02\u5718",
        "\u5408\u5531",
        "\u6307\u6325",
        "\u6307\u63ee",
        "\u5409\u4ed6",
        "\u8d1d\u65af",
        "\u8c9d\u65af",
        "\u9f13\u624b",
        "\u94a2\u7434",
        "\u92fc\u7434",
        "\u4e2d\u63d0\u7434",
        "\u5927\u63d0\u7434",
        "\u5c0f\u63d0\u7434",
        "\u5f26\u4e50",
        "\u5f26\u6a02"
    )

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
            debug("Skip bridge payload without timed lyric, id=${song.id.orEmpty()}")
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

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
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
            context.sendBroadcast(intent)
        }.onSuccess {
            debug(
                "Sent Netease bridge payload, generation=$trackGeneration, " +
                    "id=${song.id.orEmpty()}, " +
                    "lines=${lyricLines.size}/${song.lyrics?.size ?: 0}, " +
                    "mode=$lyricMode, rawMode=${if (rawLyric == lyric) "plain" else "enhanced"}, " +
                    "rawChars=${rawLyric.length}, transChars=${translationLyric.length}, " +
                    "first=${shortenForLog(lyricLines.firstOrNull()?.text.orEmpty())}"
            )
        }.onFailure { e ->
            payloadGate.forget(payloadKey)
            Log.w(TAG, "Failed to send Netease bridge payload, id=${song.id.orEmpty()}", e)
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
            context.sendBroadcast(intent)
        }.onFailure { e ->
            playbackStateGate.reset()
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

    private fun Intent.putBridgeDeclaration(context: Context) {
        putExtra("protocolVersion", BRIDGE_PROTOCOL_VERSION)
        putExtra("playerPackage", context.packageName)
        putExtra("capabilities", BRIDGE_CAPABILITIES)
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
    }

    private fun shouldSendEnhancedRawLyric(lyricMode: Int): Boolean {
        return lyricMode == LYRIC_MODE_TRANSLATION || lyricMode == LYRIC_MODE_ROMA
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

    private fun isLikelyMetadataLine(
        line: RichLyricLine,
        song: Song,
        removedEarlyCredit: Boolean
    ): Boolean {
        val text = cleanPlainText(line.text.orEmpty())
        if (text.isBlank()) return true
        if (line.begin <= EARLY_METADATA_WINDOW_MS) {
            if (looksLikeTitleArtistCredit(text) || looksLikeKnownTrackCredit(text, song)) {
                return true
            }
        }
        if (looksLikeCreditRoleLine(text)) return true
        return removedEarlyCredit &&
            line.begin <= EARLY_METADATA_WINDOW_MS &&
            looksLikeArtistCreditContinuation(text)
    }

    private fun looksLikeTitleArtistCredit(text: String): Boolean {
        val value = normalizeSpaces(text)
        if (value.length < 5 || value.length > 180) return false
        val separator = creditSeparatorIndex(value)
        if (separator <= 0 || separator + 1 >= value.length) return false
        val title = value.substring(0, separator).trim()
        var artist = value.substring(separator + 1).trim()
        if (artist.startsWith("-") || artist.startsWith("\u2013") || artist.startsWith("\u2014")) {
            artist = artist.substring(1).trim()
        }
        return title.isNotBlank() &&
            artist.isNotBlank() &&
            containsLetter(title) &&
            containsLetter(artist) &&
            !endsLikeSentence(value)
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
        val value = normalizeSpaces(text)
        if (value.length > 160) return false

        val lower = value.lowercase(Locale.ROOT)
        val colon = firstColonIndex(value)
        if (colon > 0 && colon <= 48) {
            val prefix = lower.substring(0, colon).trim()
            if (containsCreditRoleKeyword(prefix)) {
                return true
            }
        }
        if (startsWithCreditRoleKeyword(lower)) return true
        return lower.startsWith("op ") ||
            lower.startsWith("sp ") ||
            lower.startsWith("op:") ||
            lower.startsWith("sp:")
    }

    private fun looksLikeArtistCreditContinuation(text: String): Boolean {
        val value = normalizeSpaces(text)
        if (value.length < 3 || value.length > 96 || endsLikeSentence(value)) return false
        return (value.indexOf('/') >= 0 ||
            value.indexOf('&') >= 0 ||
            value.indexOf(',') >= 0 ||
            value.indexOf('\u3001') >= 0) &&
            containsLetter(value) &&
            countWhitespaceRuns(value) <= 4
    }

    private fun normalizeSpaces(value: String): String {
        return cleanPlainText(value).replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun creditSeparatorIndex(value: String): Int {
        val separators = arrayOf(" - ", " \u2013 ", " \u2014 ", "- ", "\u2013 ", "\u2014 ")
        var best = -1
        separators.forEach { separator ->
            val index = value.lastIndexOf(separator)
            if (index > 0 && index + separator.length < value.length) {
                best = max(best, index)
            }
        }
        return best
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

    private fun startsWithCreditRoleKeyword(value: String): Boolean {
        return creditRoleKeywords.any { keyword ->
            val lowerKeyword = keyword.lowercase(Locale.ROOT)
            value == lowerKeyword ||
                value.startsWith("$lowerKeyword:") ||
                value.startsWith("$lowerKeyword\uff1a") ||
                value.startsWith("$lowerKeyword by ") ||
                (isStrongStandaloneCreditRole(lowerKeyword) && value.startsWith("$lowerKeyword "))
        }
    }

    private fun isStrongStandaloneCreditRole(value: String): Boolean {
        if (value.length <= 3) return false
        return value == "lyrics" ||
            value == "lyricist" ||
            value == "composer" ||
            value == "arranger" ||
            value == "producer" ||
            value == "produced" ||
            value == "production" ||
            value == "editor" ||
            value == "editing" ||
            value == "engineer" ||
            value == "engineered" ||
            value == "audio" ||
            value == "sound" ||
            value == "musician" ||
            value == "musicians" ||
            value == "performer" ||
            value == "performed" ||
            value == "performance" ||
            value == "publisher" ||
            value == "copyright" ||
            value == "\u4f5c\u8bcd" ||
            value == "\u4f5c\u8a5e" ||
            value == "\u4f5c\u66f2" ||
            value == "\u7f16\u66f2" ||
            value == "\u7de8\u66f2" ||
            value == "\u5236\u4f5c" ||
            value == "\u88fd\u4f5c" ||
            value == "\u51fa\u54c1" ||
            value == "\u7f16\u8f91" ||
            value == "\u7de8\u8f2f" ||
            value == "\u97f3\u9891" ||
            value == "\u97f3\u983b" ||
            value == "\u5de5\u7a0b" ||
            value == "\u5de5\u7a0b\u5e08" ||
            value == "\u5de5\u7a0b\u5e2b" ||
            value == "\u6f14\u594f" ||
            value == "\u4e50\u624b" ||
            value == "\u6a02\u624b" ||
            value == "\u76d1\u5236" ||
            value == "\u76e3\u88fd" ||
            value == "\u6f14\u5531" ||
            value == "\u6b4c\u624b"
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
        return "netease-cloud-music:$id:$hash"
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
