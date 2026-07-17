/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.extensions.bridge.BridgeInlineSegmentPolicy
import io.github.proify.extensions.bridge.retainBridgeLyricLines
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

object PowerampLyricInfoPublisher : YukiBaseHooker() {
    private const val TAG = "PowerampLyricInfoPublisher"
    private const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    private const val SOURCE_POWERAMP = "lyricprovider/poweramp-music"

    private val lock = Any()

    private val selfPublishing = ThreadLocal<Boolean>()
    private var lastSession: MediaSession? = null
    private var lastMetadata: MediaMetadata? = null
    private var expectedTrackId: String = ""
    private var expectedGeneration: Long = 0L
    private var latestSong: Song? = null
    private var latestSongGeneration: Long = 0L
    private var latestPreparedPayload: PowerampPreparedLyricPayload? = null
    private var lastPublishedFingerprint: String = ""
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val COMPARABLE_TEXT_REGEX = Regex("[\\p{Punct}\\s]+")

    override fun onHook() = Unit

    fun install() {
        runCatching {
            "android.media.session.MediaSession".toClass()
                .resolve()
                .firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
                        val session = instance as? MediaSession ?: return@after
                        onSetMetadata(session, metadata)
                    }
                }
        }.onSuccess {
            PowerampLog.debug(tag = TAG, msg = "Hooked MediaSession.setMetadata for lyricInfo publishing")
        }.onFailure {
            PowerampLog.error(tag = TAG, msg = "Failed to hook MediaSession.setMetadata for lyricInfo publishing", e = it)
        }
    }

    fun onTrackChanged(metadata: TrackMetadata, generation: Long) {
        synchronized(lock) {
            expectedTrackId = metadata.id.trim()
            expectedGeneration = generation
            latestSong = null
            latestSongGeneration = 0L
            latestPreparedPayload = null
            lastPublishedFingerprint = ""
        }
    }

    fun onLyricReady(
        context: Context?,
        song: Song,
        generation: Long,
        bridgeSourceLyric: String? = null
    ) {
        if (!synchronized(lock) { isExpectedTrackLocked(song, generation) }) {
            PowerampLog.debug(
                tag = TAG,
                msg = "Skip stale lyric before payload preparation, generation=$generation, " +
                    "id=${song.id.orEmpty()}"
            )
            return
        }
        val preparedPayload = prepareLyricPayload(song, generation, bridgeSourceLyric)
        if (preparedPayload == null) {
            PowerampLog.debug(
                tag = TAG,
                msg = "Skip lyricInfo publish without timed lyric, id=${song.id.orEmpty()}"
            )
            return
        }
        val accepted = synchronized(lock) {
            if (!isExpectedTrackLocked(song, generation)) {
                return@synchronized false
            }
            latestSong = song
            latestSongGeneration = generation
            latestPreparedPayload = preparedPayload
            true
        }
        if (!accepted) {
            PowerampLog.debug(
                tag = TAG,
                msg = "Skip stale prepared lyric payload, generation=$generation, id=${song.id.orEmpty()}"
            )
            return
        }
        PowerampSaltLyricBridge.sendLyricReady(context, preparedPayload)
        tryPublish("lyric-ready")
    }

    private fun isExpectedTrackLocked(song: Song, generation: Long): Boolean {
        return generation == expectedGeneration &&
            (expectedTrackId.isBlank() || song.id.orEmpty().trim() == expectedTrackId)
    }

    private fun onSetMetadata(session: MediaSession, metadata: MediaMetadata) {
        if (selfPublishing.get() == true) {
            return
        }
        synchronized(lock) {
            lastSession = session
            lastMetadata = metadata
        }
        tryPublish("metadata")
    }

    private fun tryPublish(reason: String) {
        val request = synchronized(lock) {
            val session = lastSession ?: return
            val metadata = lastMetadata ?: return
            val song = latestSong ?: return
            val preparedPayload = latestPreparedPayload ?: return
            if (latestSongGeneration != expectedGeneration ||
                preparedPayload.trackGeneration != expectedGeneration
            ) {
                return
            }
            if (!matchesCurrentTrack(metadata, song)) {
                PowerampLog.debug(
                    tag = TAG,
                    msg = "Wait to publish lyricInfo: metadata/song mismatch, reason=$reason, " +
                        "mediaId=${metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()}, " +
                        "songId=${song.id.orEmpty()}"
                )
                return
            }

            val payload = preparedPayload.lyricInfo

            val fingerprint = buildFingerprint(metadata, payload)
            if (fingerprint == lastPublishedFingerprint) {
                return
            }

            val patched = buildLyricInfoOnlyMetadata(metadata, payload)
            PublishRequest(
                session = session,
                metadata = patched,
                fingerprint = fingerprint,
                lyricInfoChars = payload.length,
                mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                title = song.name.orEmpty(),
                trackGeneration = preparedPayload.trackGeneration,
                reason = reason
            )
        }

        runCatching {
            selfPublishing.set(true)
            request.session.setMetadata(request.metadata)
        }.onSuccess {
            synchronized(lock) {
                if (request.trackGeneration == expectedGeneration) {
                    lastPublishedFingerprint = request.fingerprint
                }
            }
            PowerampLog.debug(
                tag = TAG,
                msg = "Published Poweramp lyricInfo, reason=${request.reason}, " +
                    "mediaId=${request.mediaId}, title=${shortenForLog(request.title)}, " +
                    "chars=${request.lyricInfoChars}, artworkBitmap=false"
            )
        }.onFailure {
            PowerampLog.error(tag = TAG, msg = "Failed to publish Poweramp lyricInfo", e = it)
        }.also {
            selfPublishing.remove()
        }
    }

    private fun matchesCurrentTrack(metadata: MediaMetadata, song: Song): Boolean {
        val metadataTrackId = mediaIdTrackId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        val songId = song.id.orEmpty().trim()
        if (expectedTrackId.isNotBlank() && songId != expectedTrackId) return false
        if (metadataTrackId.isNotBlank() && songId.isNotBlank() && metadataTrackId == songId) return true

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return normalizeTrackComponent(title) == normalizeTrackComponent(song.name) &&
            normalizeTrackComponent(artist) == normalizeTrackComponent(song.artist)
    }

    private fun mediaIdTrackId(mediaId: String?): String {
        val value = mediaId.orEmpty().trim()
        if (value.isBlank()) return ""
        return value.substringAfterLast('/').takeIf { it.isNotBlank() } ?: value
    }

    private fun prepareLyricPayload(
        song: Song,
        generation: Long,
        bridgeSourceLyric: String?
    ): PowerampPreparedLyricPayload? {
        prepareInlineBracketLyricPayload(song, generation, bridgeSourceLyric)?.let {
            return it
        }
        val lyricLines = filteredLyricLines(song)
        val rawLyric = toEnhancedLrc(song, lyricLines)
        if (!containsTimedLrc(rawLyric)) return null

        val lyric = toPlainLrc(song, lyricLines)
        val translationLyric = toTranslationLrc(song, lyricLines)
        return buildPreparedPayload(song, generation, lyric, rawLyric, translationLyric)
    }

    private fun prepareInlineBracketLyricPayload(
        song: Song,
        generation: Long,
        bridgeSourceLyric: String?
    ): PowerampPreparedLyricPayload? {
        val parsedLines = PowerampBridgeTaggedLyricParser.parse(bridgeSourceLyric)
        if (parsedLines.isEmpty()) return null

        val lyricLines = retainBridgeLyricLines(
            parsedLines,
            { it.text },
            { it.begin }
        )
        if (lyricLines.isEmpty()) return null
        PowerampLog.debug(
            tag = TAG,
            msg = "Normalized inline bracket lyric for Bridge, lines=${lyricLines.size}, " +
                "translations=${lyricLines.count { it.translation.isNotBlank() }}"
        )

        val lyric = StringBuilder().also { builder ->
            appendMetadata(builder, song)
            lyricLines.forEach { line -> appendTimedLine(builder, line.begin, line.text) }
        }.toString()
        val rawLyric = StringBuilder().also { builder ->
            appendMetadata(builder, song)
            lyricLines.forEach { line ->
                builder.append('[')
                appendLrcTime(builder, line.begin)
                builder.append(']')
                line.segments.forEach { segment ->
                    builder.append('<')
                    appendLrcTime(builder, segment.begin)
                    builder.append('>').append(cleanInlineSegment(segment.text))
                }
                if (line.end > line.begin) {
                    builder.append('<')
                    appendLrcTime(builder, line.end)
                    builder.append('>')
                }
                builder.append('\n')
            }
        }.toString()
        val translationLyric = StringBuilder().also { builder ->
            appendMetadata(builder, song)
            lyricLines.forEach { line ->
                if (line.translation.isNotBlank() && line.translation.trim() != "//") {
                    appendTimedLine(builder, line.begin, line.translation)
                }
            }
        }.toString()
        if (!containsTimedLrc(rawLyric)) return null
        return buildPreparedPayload(song, generation, lyric, rawLyric, translationLyric)
    }

    private fun buildPreparedPayload(
        song: Song,
        generation: Long,
        lyric: String,
        rawLyric: String,
        translationLyric: String
    ): PowerampPreparedLyricPayload {
        val trackKey = buildTrackKey(song.name, song.artist)
        val lyricInfo = JSONObject()
            .put("songName", song.name.orEmpty())
            .put("artist", song.artist.orEmpty())
            .put("songId", song.id.orEmpty())
            .put("lyric", lyric)
            .put("rawLyric", rawLyric)
            .put("translationLyric", translationLyric)
            .put("provider", SOURCE_POWERAMP)
            .put("trackKey", trackKey)
            .put("sessionGeneration", generation)
            .toString()
        return PowerampPreparedLyricPayload(
            mediaId = song.id.orEmpty(),
            title = song.name.orEmpty(),
            artist = song.artist.orEmpty(),
            duration = song.duration,
            trackKey = trackKey,
            trackGeneration = generation,
            lyric = lyric,
            rawLyric = rawLyric,
            translationLyric = translationLyric,
            lyricInfo = lyricInfo
        )
    }

    private fun buildFingerprint(metadata: MediaMetadata, lyricInfo: String): String {
        return metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty() +
            ':' + metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty() +
            ':' + lyricInfo.hashCode()
    }

    private fun buildLyricInfoOnlyMetadata(
        source: MediaMetadata,
        lyricInfo: String
    ): MediaMetadata {
        val builder = MediaMetadata.Builder()
        TEXT_METADATA_KEYS.forEach { key ->
            val value = source.getText(key)
            if (value != null) {
                builder.putText(key, value)
            }
        }
        LONG_METADATA_KEYS.forEach { key ->
            if (source.containsKey(key)) {
                builder.putLong(key, source.getLong(key))
            }
        }
        builder.putText(METADATA_KEY_LYRIC_INFO, lyricInfo)
        return builder.build()
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
            appendLrcTime(builder, line.begin)
            builder.append(']')
            words.forEach wordLoop@{ word ->
                val segment = cleanInlineSegment(word.text)
                if (BridgeInlineSegmentPolicy.appendStandaloneWhitespace(builder, segment)) {
                    return@wordLoop
                }
                builder.append('<')
                appendLrcTime(builder, word.begin)
                builder.append('>')
                    .append(segment)
            }
            val end = inferEnhancedLineEnd(line, words)
            if (end > line.begin) {
                builder.append('<')
                appendLrcTime(builder, end)
                builder.append('>')
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
        lines.forEach { line ->
            val translation = line.translation?.takeIf { it.isNotBlank() && it.trim() != "//" }
                ?: secondaryTranslationFor(line)
            if (!translation.isNullOrBlank()) {
                appendTimedLine(builder, line.begin, translation)
            }
        }
        return builder.toString()
    }

    private fun filteredLyricLines(song: Song): List<RichLyricLine> {
        return retainBridgeLyricLines(song.lyrics)
    }

    private fun secondaryTranslationFor(line: RichLyricLine): String? {
        val primary = cleanPlainText(line.text.orEmpty())
        val candidate = cleanPlainText(line.secondary.orEmpty())
        if (candidate.isBlank() ||
            candidate == "//" ||
            normalizeComparableText(candidate) == normalizeComparableText(primary) ||
            isLikelyRomanization(primary, candidate)
        ) {
            return null
        }
        return candidate
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
        appendLrcTime(builder, timeMillis)
        builder.append(']')
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
        return builder.toString()
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

    private fun normalizeComparableText(value: String): String {
        return cleanPlainText(value)
            .lowercase(Locale.ROOT)
            .replace(COMPARABLE_TEXT_REGEX, "")
    }

    private fun isLikelyRomanization(primary: String, candidate: String): Boolean {
        if (!containsJapanese(primary)) return false
        val clean = cleanPlainText(candidate)
        if (clean.isBlank() ||
            !clean.all { it.isLetter() || it.isWhitespace() || it == '\'' || it == '-' }
        ) {
            return false
        }
        val words = clean.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (words.size < 3) return false
        val shortWords = words.count { it.length <= 3 }
        return shortWords >= words.size * 2 / 3
    }

    private fun containsJapanese(value: String): Boolean {
        return value.any {
            it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff'
        }
    }

    private fun containsTimedLrc(value: String): Boolean {
        return TIMED_LRC_REGEX.containsMatchIn(value)
    }

    private fun appendLrcTime(builder: StringBuilder, timeMillis: Long) {
        val safeTime = max(0L, timeMillis)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        val millis = safeTime % 1000L
        if (minutes < 10L) builder.append('0')
        builder.append(minutes).append(':')
        if (seconds < 10L) builder.append('0')
        builder.append(seconds).append('.')
        if (millis < 100L) builder.append('0')
        if (millis < 10L) builder.append('0')
        builder.append(millis)
    }

    private fun shortenForLog(value: String): String {
        val clean = cleanPlainText(value)
        return if (clean.length <= 48) clean else clean.substring(0, 45) + "..."
    }

    private data class PublishRequest(
        val session: MediaSession,
        val metadata: MediaMetadata,
        val fingerprint: String,
        val lyricInfoChars: Int,
        val mediaId: String,
        val title: String,
        val trackGeneration: Long,
        val reason: String
    )

    private val TEXT_METADATA_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_ALBUM,
        MediaMetadata.METADATA_KEY_AUTHOR,
        MediaMetadata.METADATA_KEY_WRITER,
        MediaMetadata.METADATA_KEY_COMPOSER,
        MediaMetadata.METADATA_KEY_COMPILATION,
        MediaMetadata.METADATA_KEY_DATE,
        MediaMetadata.METADATA_KEY_GENRE,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        MediaMetadata.METADATA_KEY_MEDIA_ID,
        MediaMetadata.METADATA_KEY_MEDIA_URI
    )

    private val LONG_METADATA_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_DURATION,
        MediaMetadata.METADATA_KEY_YEAR,
        MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_NUM_TRACKS,
        MediaMetadata.METADATA_KEY_DISC_NUMBER,
        MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE
    )
}
