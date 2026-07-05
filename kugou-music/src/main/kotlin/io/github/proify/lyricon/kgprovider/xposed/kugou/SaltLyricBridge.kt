/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale
import kotlin.math.max

object SaltLyricBridge {
    private const val TAG = "Lyricon_KuGouBridge"
    private const val ACTION_EXTERNAL_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_CAPTURED"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val KUGOU_PACKAGE = "com.kugou.android"
    private const val KUGOU_CONCEPT_PACKAGE = "com.kugou.android.lite"
    private const val SOURCE_KUGOU = "lyricprovider/kugou-music"
    private const val SOURCE_KUGOU_CONCEPT = "lyricprovider/kugou-concept-music"
    private const val MAX_REASONABLE_DURATION_MS = 24L * 60L * 60L * 1000L

    fun sendTrackChanged(
        context: Context?,
        metadata: MetadataData?,
        trackGeneration: Long
    ) {
        if (context == null || metadata == null || trackGeneration <= 0L) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
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

        val lyricLines = filteredLyricLines(song)
        val rawLyric = toEnhancedLrc(song, lyricLines)
        if (!containsTimedLrc(rawLyric)) {
            debug("Skip bridge payload without timed lyric, id=${song.id.orEmpty()}")
            return
        }

        val lyric = toPlainLrc(song, lyricLines)
        val translationLyric = toTranslationLrc(song, lyricLines)
        val source = sourceForPackage(context.packageName)
        val requestId = buildRequestId(source, song, rawLyric, translationLyric)

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putExtra("source", source)
            putExtra("eventType", "lyricReady")
            putExtra("requestId", requestId)
            putExtra("mediaId", song.id.orEmpty())
            putExtra("mediaUri", mediaUri.orEmpty())
            putExtra("trackKey", buildTrackKey(song.name, song.artist))
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
                "Sent KuGou bridge payload, source=$source, id=${song.id.orEmpty()}, " +
                    "lines=${lyricLines.size}/${song.lyrics?.size ?: 0}, " +
                    "rawChars=${rawLyric.length}, transChars=${translationLyric.length}"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to send KuGou bridge payload, id=${song.id.orEmpty()}", e)
        }
    }

    fun sendPlaybackState(context: Context?, state: PlaybackState?) {
        if (context == null || state == null) return

        val intent = Intent(ACTION_EXTERNAL_LYRIC_CAPTURED).apply {
            setPackage(SYSTEMUI_PACKAGE)
            putExtra("source", sourceForPackage(context.packageName))
            putExtra("playbackState", state.state)
            putExtra("playbackPosition", state.position)
            putExtra("playbackSpeed", state.playbackSpeed)
            putExtra("playbackLastPositionUpdateTime", state.lastPositionUpdateTime)
            putExtra("capturedAt", System.currentTimeMillis())
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onFailure { e ->
            Log.w(TAG, "Failed to send KuGou playback state, state=${state.state}", e)
        }
    }

    private fun sourceForPackage(packageName: String?): String {
        return when (packageName) {
            KUGOU_CONCEPT_PACKAGE -> SOURCE_KUGOU_CONCEPT
            KUGOU_PACKAGE -> SOURCE_KUGOU
            else -> SOURCE_KUGOU
        }
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
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsTimedLrc(value: String): Boolean {
        return Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
            .containsMatchIn(value)
    }

    private fun debug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }

    private fun formatLrcTime(timeMillis: Long): String {
        val safeTime = max(0L, timeMillis)
        val minutes = safeTime / 60_000L
        val seconds = (safeTime % 60_000L) / 1_000L
        val millis = safeTime % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
