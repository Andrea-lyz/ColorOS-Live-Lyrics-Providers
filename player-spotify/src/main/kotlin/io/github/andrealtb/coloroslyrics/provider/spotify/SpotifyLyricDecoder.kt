/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.LatinSyllableSpanMerger
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

object SpotifyLyricDecoder {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun decode(body: String, track: TrackIdentity): SpotifyPublication? {
        val response = runCatching {
            json.decodeFromString(SpotifyLyricResponse.serializer(), body)
        }.getOrNull() ?: return null
        val lyrics = response.lyrics ?: return null
        val lines = lyrics.lines.mapIndexedNotNull { index, line ->
            toRichLine(line, lyrics.lines.getOrNull(index + 1)?.resolvedStartMs())
        }
        if (lines.isEmpty()) return null
        return SpotifyPublication(
            rawLyric = "",
            translationLyric = "",
            lines = lines,
            capturedTrack = track,
            sourceName = lyrics.provider?.takeIf { it.isNotBlank() } ?: "color-lyrics",
            syncType = lyrics.syncType.orEmpty()
        )
    }

    internal fun toRichLine(line: SpotifyLyricLine, nextStartMs: Long?): RichLyricLine? {
        val text = line.words?.trim().orEmpty()
        if (text.isEmpty()) return null
        val begin = line.resolvedStartMs()
        val explicitEnd = line.resolvedEndMs()
        val end = when {
            explicitEnd > begin -> explicitEnd
            nextStartMs != null && nextStartMs > begin -> nextStartMs
            else -> begin + 5_000L
        }
        val words = syllableWords(line, begin, end)
        return RichLyricLine(
            begin = begin,
            end = end,
            duration = (end - begin).coerceAtLeast(0L),
            text = text,
            words = words,
            secondary = null
        )
    }

    private fun syllableWords(
        line: SpotifyLyricLine,
        lineBegin: Long,
        lineEnd: Long
    ): List<LyricWord>? {
        if (line.syllables.isEmpty()) return null
        val spans = mutableListOf<LatinSyllableSpanMerger.Span>()
        line.syllables.forEach { syllable ->
            val text = syllable.displayText() ?: return@forEach
            val begin = syllable.resolvedStartMs().coerceAtLeast(lineBegin)
            val explicitEnd = syllable.resolvedEndMs()
            val end = if (explicitEnd > begin) explicitEnd else begin
            if (text.startsWith(' ') && spans.isNotEmpty()) {
                val previous = spans.removeAt(spans.lastIndex)
                spans += previous.copy(hasTrailingSpace = true)
            }
            spans += LatinSyllableSpanMerger.Span(
                text = text,
                begin = begin,
                end = end,
                hasTrailingSpace = text.endsWith(' ')
            )
        }
        if (spans.isEmpty()) return null
        val merged = LatinSyllableSpanMerger.merge(spans)
        val words = merged.mapIndexed { index, span ->
            val nextBegin = merged.getOrNull(index + 1)?.begin
            val end = when {
                span.end > span.begin -> span.end
                nextBegin != null && nextBegin > span.begin -> nextBegin
                else -> lineEnd
            }
            LyricWord(
                begin = span.begin,
                end = end,
                duration = (end - span.begin).coerceAtLeast(0L),
                text = span.text.trim().ifEmpty { span.text }
            )
        }
        return words.takeIf { it.isNotEmpty() }
    }
}

internal object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return 0L
        return primitive.longOrNull ?: primitive.content.toLongOrNull() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

@Serializable
internal data class SpotifyLyricResponse(
    val lyrics: SpotifyLyricsData? = null
)

@Serializable
internal data class SpotifyLyricsData(
    val syncType: String? = null,
    val lines: List<SpotifyLyricLine> = emptyList(),
    val provider: String? = null,
    val language: String? = null
)

@Serializable
internal data class SpotifyLyricLine(
    @Serializable(with = FlexibleLongSerializer::class)
    val startTimeMs: Long = 0L,
    val words: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    val endTimeMs: Long = 0L,
    val transliteratedWords: String? = null,
    val syllables: List<SpotifyLyricSyllable> = emptyList()
) {
    fun resolvedStartMs(): Long = startTimeMs.coerceAtLeast(0L)
    fun resolvedEndMs(): Long = endTimeMs.coerceAtLeast(0L)
}

@Serializable
internal data class SpotifyLyricSyllable(
    @Serializable(with = FlexibleLongSerializer::class)
    val startTimeMs: Long = 0L,
    @Serializable(with = FlexibleLongSerializer::class)
    val endTimeMs: Long = 0L,
    val chars: String? = null,
    val text: String? = null,
    val words: String? = null,
    @SerialName("syllable")
    val syllable: String? = null
) {
    fun displayText(): String? =
        sequenceOf(chars, text, words, syllable)
            .map { it?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull { it != null }

    fun resolvedStartMs(): Long = startTimeMs.coerceAtLeast(0L)
    fun resolvedEndMs(): Long = endTimeMs.coerceAtLeast(0L)
}
