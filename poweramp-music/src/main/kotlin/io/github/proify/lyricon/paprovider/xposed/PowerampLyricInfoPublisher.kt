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
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

object PowerampLyricInfoPublisher : YukiBaseHooker() {
    private const val TAG = "PowerampLyricInfoPublisher"
    private const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    private const val SOURCE_POWERAMP = "lyricprovider/poweramp-music"
    private const val EARLY_METADATA_WINDOW_MS = 30_000L

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
    private val ARTIST_SPLIT_REGEX = Regex("[/,&;\\uff0c\\uff1b\\u3001]")

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
            PowerampLog.info(tag = TAG, msg = "Hooked MediaSession.setMetadata for lyricInfo publishing")
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

        var removedEarlyCredit = false
        val lyricLines = parsedLines.filter { line ->
            val probe = RichLyricLine(begin = line.begin, text = line.text)
            val metadataLine = isLikelyMetadataLine(probe, song, removedEarlyCredit)
            if (metadataLine && line.begin <= EARLY_METADATA_WINDOW_MS) {
                removedEarlyCredit = true
            }
            !metadataLine
        }
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
            words.forEach { word ->
                builder.append('<')
                appendLrcTime(builder, word.begin)
                builder.append('>')
                    .append(cleanInlineSegment(word.text))
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
        val result = mutableListOf<RichLyricLine>()
        var removedEarlyCredit = false
        song.lyrics.orEmpty()
            .filter { !it.text.isNullOrBlank() }
            .sortedBy { it.begin }
            .forEach { line ->
                if (isLikelyMetadataLine(line, song, removedEarlyCredit)) {
                    if (line.begin <= EARLY_METADATA_WINDOW_MS) {
                        removedEarlyCredit = true
                    }
                } else {
                    result.add(line)
                }
            }
        return result
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
        return cleanPlainText(value)
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
        return creditRoleKeywords.any(value::contains)
    }

    private fun startsWithCreditRoleKeyword(value: String): Boolean {
        return creditRoleKeywords.any { keyword ->
            value == keyword ||
                value.startsWith("$keyword:") ||
                value.startsWith("$keyword\uff1a") ||
                value.startsWith("$keyword by ") ||
                (isStrongStandaloneCreditRole(keyword) && value.startsWith("$keyword "))
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
        return value.split(ARTIST_SPLIT_REGEX)
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
