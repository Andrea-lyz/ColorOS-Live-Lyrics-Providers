/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.proify.extensions.bridge.BridgePayloadGate
import io.github.proify.extensions.bridge.BridgePlaybackStateGate
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_KuGouBridge"
    private const val BRIDGE_DIAGNOSTICS_ENABLED = false
    private const val ACTION_EXTERNAL_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_CAPTURED"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val BRIDGE_PROTOCOL_VERSION = 2
    private const val KUGOU_PACKAGE = "com.kugou.android"
    private const val KUGOU_CONCEPT_PACKAGE = "com.kugou.android.lite"
    private const val SOURCE_KUGOU = "lyricprovider/kugou-music"
    private const val SOURCE_KUGOU_CONCEPT = "lyricprovider/kugou-concept-music"
    private const val BRIDGE_CAPABILITIES_WITH_PLAYBACK =
        "playbackState,trackGeneration,currentTrackAuthority,translationToggle"
    private const val BRIDGE_CAPABILITIES_TRACK_ONLY =
        "trackGeneration,currentTrackAuthority,translationToggle"
    private const val BRIDGE_MATCH_POLICY = "mediaId,mediaUri,trackKey,titleArtist"
    private const val BRIDGE_IDENTITY_CONFIDENCE = "currentTrack"
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val payloadGate = BridgePayloadGate()
    private val playbackStateGate = BridgePlaybackStateGate()

    fun sendTrackChanged(
        context: Context?,
        metadata: MetadataData?,
        trackGeneration: Long
    ) {
        if (context == null || metadata == null || trackGeneration <= 0L) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", sourceForPackage(context.packageName))
            putExtra("eventType", "trackChanged")
            putExtra("mediaId", metadata.identityId)
            putExtra("mediaUri", metadata.mediaUri)
            putExtra("trackKey", buildTrackKey(metadata.title, metadata.artist))
            putExtra("songName", metadata.title)
            putExtra("artist", metadata.artist)
            putExtra("duration", validDuration(metadata.duration))
            putExtra("trackGeneration", trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onSuccess {
            debug(
                "KG_ALIGN provider trackChanged gen=$trackGeneration " +
                    "key=${buildTrackKey(metadata.title, metadata.artist).take(96)} " +
                    "title=${metadata.title.take(64)} artist=${metadata.artist.take(64)}"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to send KuGou track change, generation=$trackGeneration", e)
        }
    }

    fun send(
        context: Context?,
        song: Song?,
        mediaUri: String? = null,
        trackGeneration: Long = 0L
    ) {
        if (context == null || song == null) return

        val payload = buildLyricPayload(context.packageName, song)
        if (payload == null) {
            debug("Skip bridge payload without timed lyric, id=${song.id.orEmpty()}")
            return
        }

        val requestId = buildRequestId(
            payload.source,
            song,
            payload.rawLyric,
            payload.translationLyric
        )
        val payloadKey = "${payload.source}:$trackGeneration:$requestId"
        if (!payloadGate.shouldSend(payloadKey, SystemClock.elapsedRealtime())) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putBridgeDeclaration(context)
            putExtra("source", payload.source)
            putExtra("eventType", "lyricReady")
            putExtra("requestId", requestId)
            putExtra("mediaId", song.id.orEmpty())
            putExtra("mediaUri", mediaUri.orEmpty())
            putExtra("trackKey", buildTrackKey(song.name, song.artist))
            putExtra("songName", song.name.orEmpty())
            putExtra("artist", song.artist.orEmpty())
            putExtra("duration", validDuration(song.duration))
            putExtra("lyric", payload.lyric)
            putExtra("rawLyric", payload.rawLyric)
            putExtra("translationLyric", payload.translationLyric)
            putExtra("trackGeneration", trackGeneration)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onSuccess {
            debug(
                "KG_ALIGN provider lyricReady gen=$trackGeneration " +
                    "key=${buildTrackKey(song.name, song.artist).take(96)} " +
                    "title=${song.name.orEmpty().take(64)} " +
                    "artist=${song.artist.orEmpty().take(64)} " +
                    "rawChars=${payload.rawLyric.length} " +
                    "transChars=${payload.translationLyric.length} " +
                    "lines=${payload.lyricLines.size}/${song.lyrics?.size ?: 0}"
            )
            debug(
                "Sent KuGou bridge payload, source=${payload.source}, id=${song.id.orEmpty()}, " +
                    "lines=${payload.lyricLines.size}/${song.lyrics?.size ?: 0}, " +
                    "rawChars=${payload.rawLyric.length}, transChars=${payload.translationLyric.length}"
            )
        }.onFailure { e ->
            payloadGate.forget(payloadKey)
            Log.w(TAG, "Failed to send KuGou bridge payload, id=${song.id.orEmpty()}", e)
        }
    }

    fun buildLyricInfo(
        context: Context?,
        song: Song?,
        trackGeneration: Long = 0L
    ): String? = buildLyricInfoForPackage(context?.packageName, song, trackGeneration)

    fun buildLyricInfoForPackage(
        packageName: String?,
        song: Song?,
        trackGeneration: Long = 0L
    ): String? {
        if (song == null) return null
        val payload = buildLyricPayload(packageName, song) ?: return null
        return JSONObject()
            .put("songName", song.name.orEmpty())
            .put("artist", song.artist.orEmpty())
            .put("songId", song.id.orEmpty())
            .put("lyric", payload.lyric)
            .put("rawLyric", payload.rawLyric)
            .put("translationLyric", payload.translationLyric)
            .put("provider", payload.source)
            .put("trackKey", buildTrackKey(song.name, song.artist))
            .put("sessionGeneration", trackGeneration)
            .toString()
    }

    fun sendPlaybackState(
        context: Context?,
        state: PlaybackState?,
        metadata: MetadataData? = null,
        trackGeneration: Long = 0L
    ) {
        if (context == null || state == null) return
        if (!supportsBridgePlaybackStateForPackage(context.packageName)) return
        if (trackGeneration <= 0L && metadata == null) return
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
            putExtra("source", sourceForPackage(context.packageName))
            putExtra("playbackState", state.state)
            putExtra("playbackPosition", state.position)
            putExtra("playbackSpeed", state.playbackSpeed)
            putExtra("playbackLastPositionUpdateTime", state.lastPositionUpdateTime)
            putExtra("trackGeneration", trackGeneration)
            if (metadata != null) {
                putExtra("mediaId", metadata.identityId)
                putExtra("mediaUri", metadata.mediaUri)
                putExtra("trackKey", buildTrackKey(metadata.title, metadata.artist))
                putExtra("songName", metadata.title)
                putExtra("artist", metadata.artist)
                putExtra("duration", validDuration(metadata.duration))
            }
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onSuccess {
            debug(
                "KG_ALIGN provider playback gen=$trackGeneration " +
                    "state=${state.state} pos=${state.position} speed=${state.playbackSpeed} " +
                    "key=${metadata?.let { buildTrackKey(it.title, it.artist).take(96) }.orEmpty()} " +
                    "title=${metadata?.title.orEmpty().take(64)} " +
                    "artist=${metadata?.artist.orEmpty().take(64)}"
            )
        }.onFailure { e ->
            playbackStateGate.reset()
            Log.w(TAG, "Failed to send KuGou playback state, state=${state.state}", e)
        }
    }

    private fun isPlaybackInMotion(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private fun sourceForPackage(packageName: String?): String {
        return when (packageName) {
            KUGOU_CONCEPT_PACKAGE -> SOURCE_KUGOU_CONCEPT
            KUGOU_PACKAGE -> SOURCE_KUGOU
            else -> SOURCE_KUGOU
        }
    }

    private fun playerPackageForPackage(packageName: String?): String {
        return when (packageName) {
            KUGOU_CONCEPT_PACKAGE -> KUGOU_CONCEPT_PACKAGE
            KUGOU_PACKAGE -> KUGOU_PACKAGE
            else -> KUGOU_PACKAGE
        }
    }

    private fun capabilitiesForPackage(packageName: String?): String {
        return when (packageName) {
            KUGOU_CONCEPT_PACKAGE -> BRIDGE_CAPABILITIES_WITH_PLAYBACK
            KUGOU_PACKAGE -> BRIDGE_CAPABILITIES_TRACK_ONLY
            else -> BRIDGE_CAPABILITIES_TRACK_ONLY
        }
    }

    private fun supportsBridgePlaybackStateForPackage(packageName: String?): Boolean {
        return packageName == KUGOU_CONCEPT_PACKAGE
    }

    private fun Intent.putBridgeDeclaration(context: Context) {
        putExtra("protocolVersion", BRIDGE_PROTOCOL_VERSION)
        putExtra("playerPackage", playerPackageForPackage(context.packageName))
        putExtra("capabilities", capabilitiesForPackage(context.packageName))
        putExtra("matchPolicy", BRIDGE_MATCH_POLICY)
        putExtra("identityConfidence", BRIDGE_IDENTITY_CONFIDENCE)
    }

    private data class BridgeLyricPayload(
        val source: String,
        val lyricLines: List<RichLyricLine>,
        val lyric: String,
        val rawLyric: String,
        val translationLyric: String
    )

    private fun buildLyricPayload(packageName: String?, song: Song): BridgeLyricPayload? {
        val lyricLines = filteredLyricLines(song)
        val rawLyric = toEnhancedLrc(song, lyricLines)
        if (!containsTimedLrc(rawLyric)) return null

        return BridgeLyricPayload(
            source = sourceForPackage(packageName),
            lyricLines = lyricLines,
            lyric = toPlainLrc(song, lyricLines),
            rawLyric = rawLyric,
            translationLyric = toTranslationLrc(song, lyricLines)
        )
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
            .mapNotNull { line ->
                val translation = line.translation?.takeIf { isUsableTranslation(it, line.text) }
                    ?: line.secondary?.takeIf { isUsableTranslation(it, line.text) }
                translation?.let { line.begin to it }
            }
            .forEach { (begin, translation) -> appendTimedLine(builder, begin, translation) }
        return builder.toString()
    }

    private fun filteredLyricLines(song: Song): List<RichLyricLine> {
        return song.lyrics.orEmpty()
            .filter { !it.text.isNullOrBlank() }
            .sortedBy { it.begin }
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
            if (word.begin + 80L < previous) return true
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
        if (line.begin > 0L &&
            wordTime >= 0L &&
            wordTime + 250L < line.begin &&
            wordTime <= max(lineDuration + 2_000L, 2_000L)
        ) {
            return line.begin + wordTime
        }
        return max(0L, wordTime)
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

    private fun buildRequestId(
        source: String,
        song: Song,
        rawLyric: String,
        translationLyric: String
    ): String {
        val id = song.id.orEmpty().ifBlank { buildTrackKey(song.name, song.artist) }
        val hash = Integer.toHexString((rawLyric + '\n' + translationLyric).hashCode())
        return "$source:$id:$hash"
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

    private fun isUsableTranslation(translation: String, primary: String?): Boolean {
        val clean = cleanPlainText(translation)
        return clean.isNotBlank() &&
            clean != "//" &&
            clean != cleanPlainText(primary.orEmpty())
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
        if (BRIDGE_DIAGNOSTICS_ENABLED || Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, message)
        }
    }

    private fun formatLrcTime(timeMillis: Long): String {
        val safeTime = max(0L, timeMillis)
        val minutes = safeTime / 60_000L
        val seconds = (safeTime % 60_000L) / 1_000L
        val millis = safeTime % 1_000L
        return buildString(9) {
            appendPadded(minutes, 2)
            append(':')
            appendPadded(seconds, 2)
            append('.')
            appendPadded(millis, 3)
        }
    }

    private fun StringBuilder.appendPadded(value: Long, width: Int) {
        val text = value.toString()
        repeat(max(0, width - text.length)) {
            append('0')
        }
        append(text)
    }
}
