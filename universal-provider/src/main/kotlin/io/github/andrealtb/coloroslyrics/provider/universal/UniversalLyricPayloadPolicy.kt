package io.github.andrealtb.coloroslyrics.provider.universal

import org.json.JSONObject

/**
 * Applies the lyric-processing switches when a payload is published; caches keep the full payload.
 *
 * `rawLyric` is the Bridge model channel, and Bridge builds its model (including the translation
 * lane) only from a timed `rawLyric`. Word timing therefore only chooses word- or line-timed
 * content; the rawLyric switch alone hands rendering back to the official SystemUI renderer.
 *
 * The optional `ttml` lane only annotates the Bridge model (duet sides, background vocals), so it
 * leaves with `rawLyric`. It never carries translations (see `TtmlLyrics.lane`), so the
 * translation switch does not need to touch it.
 */
internal object UniversalLyricPayloadPolicy {
    const val TTML_KEY = "ttml"
    /** The format the source delivered ([io.github.andrealtb.coloroslyrics.provider.universal.api.LyricFormats]). */
    const val FORMAT_KEY = "lyricFormat"
    const val TTML_CONVERTED_KEY = "ttmlConverted"

    /** Adds the TTML lane and the real format of [result] to a payload being composed. */
    fun putTtmlAndFormat(json: JSONObject, result: io.github.andrealtb.coloroslyrics.provider.universal.api.LyricResult) {
        result.ttml?.let { json.put(TTML_KEY, it) }
        if (result.format.isNotBlank()) json.put(FORMAT_KEY, result.format)
        if (result.ttmlConverted && result.ttml != null) json.put(TTML_CONVERTED_KEY, true)
    }

    /**
     * The `source` snapshot value with the payload format appended after `|`
     * (`universal/qq|QRC→TTML`); the UI splits it back into a source and a format tag.
     */
    fun sourceWithFormat(payload: JSONObject?): String {
        val source = payload?.optString("source").orEmpty().ifBlank { "none" }
        val format = payload?.optString(FORMAT_KEY).orEmpty()
        if (format.isBlank()) return source
        val converted = payload?.optBoolean(TTML_CONVERTED_KEY) == true && payload.has(TTML_KEY)
        return source + "|" + io.github.andrealtb.coloroslyrics.provider.universal.api.LyricFormats.label(format, converted)
    }

    private val WORD_STAMP = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")
    private val LINE_STAMP = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")
    private val TRANSLATION_KEYS = listOf("transLyric", "translationLyric", "translationLanguageTag")

    data class Result(
        val hadRaw: Boolean,
        val hadWordTiming: Boolean,
        val rawAttached: Boolean,
        val wordTimed: Boolean
    )

    fun apply(json: JSONObject, wordTiming: Boolean, rawLyric: Boolean, translation: Boolean): Result {
        val raw = json.optString("rawLyric")
        val plain = stripWordTiming(json.optString("lyric"))
        if (plain.isNotBlank()) json.put("lyric", plain)
        val hadWordTiming = hasWordTiming(raw)
        val published = when {
            !rawLyric -> ""
            wordTiming && hadWordTiming -> raw
            hasLineTiming(raw) -> stripWordTiming(raw)
            // Payloads cached without rawLyric still feed the Bridge line model.
            hasLineTiming(plain) -> plain
            else -> ""
        }
        if (published.isNotBlank()) json.put("rawLyric", published) else json.remove("rawLyric")
        if (published.isBlank()) {
            json.remove(TTML_KEY)
            json.remove(TTML_CONVERTED_KEY)
        }
        if (!translation) TRANSLATION_KEYS.forEach(json::remove)
        return Result(
            hadRaw = raw.isNotBlank(),
            hadWordTiming = hadWordTiming,
            rawAttached = published.isNotBlank(),
            wordTimed = hasWordTiming(published)
        )
    }

    fun stripWordTiming(value: String): String = value.replace(WORD_STAMP, "")

    private fun hasWordTiming(value: String): Boolean = WORD_STAMP.containsMatchIn(value)

    private fun hasLineTiming(value: String): Boolean = LINE_STAMP.containsMatchIn(value)
}
