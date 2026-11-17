package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.core.publisher.TtmlPayloadNormalizer
import io.github.andrealtb.coloroslyrics.provider.parser.ttml.TtmlParser
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns a TTML document into a [LyricResult].
 *
 * The timed LRC lanes stay the authority for text and timing; the document itself rides along in
 * the optional `ttml` lane only so the Bridge can learn each line's duet side and background
 * vocals. Translations are lifted into the regular translation lane, so the lane is published
 * without them and the translation switch keeps working the same way for every source.
 */
internal object TtmlLyrics {
    /**
     * Below the provider-core cap: the universal payload is also cached on disk and handed
     * through a broadcast when a lyric is picked by hand.
     */
    const val MAX_LANE_CHARS = 120_000

    private val TRANSLATION_BLOCK = Regex("""<translations\b[^>]*/>|<translations\b[^>]*>[\s\S]*?</translations>""")
    private val LOOSE_TRANSLATION = Regex("""<translation\b[^>]*/>|<translation\b[^>]*>[\s\S]*?</translation>""")
    private val SIDE_TEXT_SPAN = Regex(
        """<span\b[^>]*\brole\s*=\s*["']x-(?:translation|roman)["'][^>]*>[\s\S]*?</span>"""
    )

    fun toResult(
        document: String?,
        query: TrackQuery,
        source: String,
        sourceTrackId: String?
    ): LyricResult? {
        if (!TtmlPayloadNormalizer.looksLikeTtml(document)) return null
        val parsed = TtmlParser.parse(document) ?: return null
        if (parsed.lines.isEmpty() || parsed.plainLrc.isBlank()) return null
        return LyricResult(
            title = query.title,
            artist = query.artist,
            lyric = parsed.plainLrc,
            rawLyric = parsed.enhancedLrc,
            translationLyric = translationLrc(document!!, Locale.getDefault()),
            source = source,
            sourceTrackId = sourceTrackId,
            ttml = lane(document),
            format = LyricFormats.TTML
        )
    }

    /** The document as published in `lyricInfo.ttml`, or null when it cannot be published. */
    fun lane(document: String?): String? {
        val normalized = TtmlPayloadNormalizer.normalize(document) ?: return null
        val stripped = normalized
            .replace(TRANSLATION_BLOCK, "")
            .replace(LOOSE_TRANSLATION, "")
            .replace(SIDE_TEXT_SPAN, "")
        return stripped.takeIf { it.length <= MAX_LANE_CHARS }
    }

    /**
     * Translation lines in the device language, keyed by their line start.
     *
     * Apple Music lists translations in the head (`<translation xml:lang>` with one `<text for>`
     * per line key); AMLL-style documents put an `x-translation` span inside the line. A
     * `replacement` translation is localized lyrics, not a translation, and is skipped, as is a
     * translation in another language than the device's.
     */
    fun translationLrc(document: String, locale: Locale): String? = runCatching {
        val doc = secureFactory().newDocumentBuilder().parse(document.byteInputStream())
        val starts = LinkedHashMap<String, Long>()
        val inline = mutableListOf<Pair<Long, String>>()
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val begin = parseTime(attribute(p, "begin")) ?: continue
            val key = attribute(p, "key")
            if (key.isNotEmpty()) starts[key] = begin
            val spans = p.getElementsByTagNameNS("*", "span")
            for (j in 0 until spans.length) {
                val span = spans.item(j) as? Element ?: continue
                if (attribute(span, "role") != "x-translation") continue
                val lang = attribute(span, "lang")
                if (lang.isNotEmpty() && !sameLanguage(lang, locale)) continue
                val text = span.textContent.orEmpty().trim()
                if (text.isNotEmpty()) inline += begin to text
            }
        }

        val rows = if (inline.isNotEmpty()) inline else headTranslation(doc, starts, locale)
        rows.sortedBy { it.first }
            .joinToString("\n") { "[" + WordTimedLyricConverter.formatMs(it.first) + "]" + it.second }
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun headTranslation(
        doc: org.w3c.dom.Document,
        starts: Map<String, Long>,
        locale: Locale
    ): List<Pair<Long, String>> {
        if (starts.isEmpty()) return emptyList()
        val translations = doc.getElementsByTagNameNS("*", "translation")
        val candidates = (0 until translations.length)
            .mapNotNull { translations.item(it) as? Element }
            .filter { attribute(it, "type") != "replacement" }
            .filter { sameLanguage(attribute(it, "lang"), locale) }
        val chosen = candidates.maxByOrNull { languageScore(attribute(it, "lang"), locale) }
            ?: return emptyList()
        val texts = chosen.getElementsByTagNameNS("*", "text")
        return (0 until texts.length).mapNotNull { index ->
            val text = texts.item(index) as? Element ?: return@mapNotNull null
            val start = starts[attribute(text, "for")] ?: return@mapNotNull null
            val value = text.textContent.orEmpty().trim()
            if (value.isEmpty()) null else start to value
        }
    }

    internal fun sameLanguage(tag: String, locale: Locale): Boolean {
        if (tag.isBlank()) return false
        return Locale.forLanguageTag(tag).language.equals(locale.language, ignoreCase = true)
    }

    /** Prefers the script the device reads: Simplified for mainland China and Singapore. */
    private fun languageScore(tag: String, locale: Locale): Int {
        val candidate = Locale.forLanguageTag(tag)
        if (!candidate.language.equals("zh", ignoreCase = true)) return 1
        val wantsTraditional = locale.script.equals("Hant", ignoreCase = true) ||
            locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
        val isTraditional = candidate.script.equals("Hant", ignoreCase = true) ||
            candidate.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
        return if (wantsTraditional == isTraditional) 2 else 1
    }

    /** Reads an attribute by local name, whatever prefix (`itunes:key`, `lrc:key`, `ttm:role`). */
    private fun attribute(element: Element, localName: String): String {
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val node = attributes.item(i)
            val name = node.localName ?: node.nodeName.substringAfterLast(':')
            if (name == localName) return node.nodeValue.orEmpty().trim()
        }
        return ""
    }

    internal fun parseTime(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return runCatching {
            when {
                text.endsWith("ms") -> text.dropLast(2).toDouble()
                text.endsWith("s") && !text.contains(':') -> text.dropLast(1).toDouble() * 1000
                else -> {
                    val parts = text.split(':')
                    var seconds = 0.0
                    parts.forEach { seconds = seconds * 60 + it.toDouble() }
                    seconds * 1000
                }
            }.let { Math.round(it) }
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
        isExpandEntityReferences = false
    }
}
