/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.EnhanceLrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import io.github.andrealtb.coloroslyrics.provider.parser.krc.KrcDecryptor
import io.github.andrealtb.coloroslyrics.provider.parser.krc.KrcParser
import io.github.andrealtb.coloroslyrics.provider.parser.ttml.TtmlParser
import android.util.Base64
import java.util.Locale

object MetrolistLyricDecoder {
    private val TIMED_LRC_REGEX =
        Regex("""[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]""")

    fun containsTimedLrc(value: String?): Boolean =
        !value.isNullOrBlank() && TIMED_LRC_REGEX.containsMatchIn(value)

    fun decode(
        lyric: String?,
        capturedTrack: TrackIdentity? = null,
        sourceName: String = "unknown",
        durationMs: Long = 0L
    ): MetrolistPublication? {
        val rawLyric = lyric.orEmpty()
        if (!containsTimedLrc(rawLyric)) return null
        val primary = EnhanceLrcParser.parse(rawLyric, durationMs).lines
        if (primary.none { !it.text.isNullOrBlank() }) return null
        return MetrolistPublication(
            rawLyric = rawLyric,
            translationLyric = "",
            lines = primary,
            capturedTrack = capturedTrack?.takeUnless { it.isBlank },
            sourceName = sourceName
        )
    }

    fun decodeBetterLyricsPayload(
        payload: String?,
        capturedTrack: TrackIdentity? = null,
        sourceName: String = "BetterLyrics"
    ): MetrolistPublication? {
        val body = payload?.trim().orEmpty()
        if (body.isEmpty()) return null
        if (looksLikeTtml(body)) {
            return decodeTtml(body, capturedTrack, sourceName)
        }
        return decode(body, capturedTrack, sourceName, capturedTrack?.durationMs ?: 0L)
    }

    fun looksLikeTtml(value: String): Boolean {
        val trimmed = value.trimStart()
        if (trimmed.startsWith("<")) return true
        val probe = trimmed.take(256)
        return probe.contains("<tt") || probe.contains("<p ") || probe.contains("<p>")
    }

    fun decodeTtml(
        ttml: String?,
        capturedTrack: TrackIdentity? = null,
        sourceName: String = "BetterLyrics"
    ): MetrolistPublication? {
        val parsed = TtmlParser.parse(ttml) ?: return null
        val lines = parsed.lines.filter { !it.text.isNullOrBlank() }
        if (lines.isEmpty()) return null
        return MetrolistPublication(
            rawLyric = parsed.enhancedLrc,
            translationLyric = "",
            lines = lines,
            capturedTrack = capturedTrack?.takeUnless { it.isBlank },
            sourceName = sourceName
        )
    }

    fun decodeEncryptedKrc(
        base64Content: String,
        capturedTrack: TrackIdentity? = null,
        sourceName: String = "KuGou"
    ): MetrolistPublication? {
        val encrypted = runCatching {
            Base64.decode(base64Content, Base64.DEFAULT)
        }.getOrNull() ?: return null
        val decrypted = KrcDecryptor.decrypt(encrypted) ?: return null
        return decodeDecryptedKrc(decrypted, capturedTrack, sourceName)
    }

    fun decodeDecryptedKrc(
        decrypted: String,
        capturedTrack: TrackIdentity? = null,
        sourceName: String = "KuGou"
    ): MetrolistPublication? {
        val document = KrcParser.parse(decrypted)
        val lines = document.lines.filter { !it.text.isNullOrBlank() }
        if (lines.isEmpty()) return null
        val richLines = lines
        val rawLyric = toEnhancedLrc(richLines)
        if (!containsTimedLrc(rawLyric)) return null
        return MetrolistPublication(
            rawLyric = rawLyric,
            translationLyric = "",
            lines = richLines,
            capturedTrack = capturedTrack?.takeUnless { it.isBlank },
            sourceName = sourceName
        )
    }

    fun matchesTrackIdentity(
        capturedTrack: TrackIdentity?,
        metadataTrack: TrackIdentity?
    ): Boolean {
        val captured = capturedTrack?.takeUnless { it.isBlank } ?: return true
        return TrackIdentityPolicy.isSameTrack(captured, metadataTrack)
    }

    private fun toEnhancedLrc(lines: List<RichLyricLine>): String = buildString {
        lines.forEach { line ->
            append('[').append(formatLrcTime(line.begin)).append(']')
            val words = line.words.orEmpty().filter { !it.text.isNullOrEmpty() }
            if (words.isEmpty()) {
                append(line.text.orEmpty().trim())
            } else {
                words.forEach { word ->
                    append('<').append(formatLrcTime(word.begin)).append('>')
                    append(word.text.orEmpty())
                }
                val finalEnd = words.last().end.coerceAtLeast(words.last().begin)
                append('<').append(formatLrcTime(finalEnd)).append('>')
            }
            append('\n')
        }
    }.trimEnd()

    private fun formatLrcTime(ms: Long): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d.%03d", min, sec, millis)
    }
}
