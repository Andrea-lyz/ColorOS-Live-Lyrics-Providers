package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The official Apple Music web catalogue (`amp-api.music.apple.com`), as the music.apple.com
 * player calls it. Follows the "official" mode of the Lyrico Apple Music plugin
 * (Replica0110/Lyrico-Plugins, Apache-2.0).
 *
 * The developer token is the public WebPlay JWT embedded in the site's script bundle; lyrics also
 * need the subscriber's `media-user-token`. Tokens are never written to diagnostics.
 */
internal class AppleMusicOfficialClient(private val client: OkHttpClient) {
    class Rejected : Exception("apple music token rejected")

    /** A lyric answer; [hadLyricData] is false when the song came back with no lyric entry at all. */
    data class LyricsAnswer(val ttml: String?, val hadLyricData: Boolean)

    data class Song(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String,
        val hasSyncedLyrics: Boolean
    )

    private companion object {
        const val SITE = "https://music.apple.com"
        const val API = "https://amp-api.music.apple.com"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val INDEX_SCRIPT = listOf(
            Regex("""/assets/index-legacy[~\-][^"']+\.js"""),
            Regex("""/assets/index[~\-][^"']+\.js""")
        )
    }

    /** A valid developer token, scraped and stored again when the stored one has expired. */
    fun developerToken(generation: Long): String? {
        val auth = AppleMusicAuthState.current
        if (auth.developerTokenValid()) return auth.developerToken
        val home = getText(generation, "dev-token-home", SITE, emptyMap()) ?: return null
        val path = INDEX_SCRIPT.firstNotNullOfOrNull { it.find(home)?.value } ?: return null
        val script = getText(generation, "dev-token-script", SITE + path, mapOf("Referer" to "$SITE/")) ?: return null
        val (token, expiry) = findWebPlayToken(script) ?: return null
        AppleMusicAuthState.update { it.copy(developerToken = token, developerTokenExpiry = expiry) }
        return token
    }

    fun search(term: String, limit: Int, generation: Long): List<Song> {
        val auth = AppleMusicAuthState.current
        val token = developerToken(generation) ?: return emptyList()
        val url = "$API/v1/catalog/${auth.storefront()}/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", limit.coerceIn(1, 25).toString())
            .addQueryParameter("l", auth.language())
            .addQueryParameter("platform", "web")
            .build()
            .toString()
        val body = apiGet(generation, "search", url, token, userToken = null) ?: return emptyList()
        val data = JSONObject(body).optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")
            ?: return emptyList()
        return (0 until data.length()).mapNotNull { index -> song(data.optJSONObject(index)) }
    }

    /**
     * The song's TTML, syllable-timed when Apple has it. Throws [Rejected] when the account
     * token is refused, so the caller can mark it and fall back.
     */
    fun lyricsTtml(songId: String, generation: Long): LyricsAnswer? {
        val auth = AppleMusicAuthState.current
        if (auth.mediaUserToken.isBlank()) return null
        val token = developerToken(generation) ?: return null
        val url = "$API/v1/catalog/${auth.storefront()}/songs/$songId".toHttpUrl().newBuilder()
            .addQueryParameter("include", "syllable-lyrics,lyrics")
            .addQueryParameter("extend", "ttmlLocalizations")
            .addQueryParameter("l", auth.language())
            .addQueryParameter("platform", "web")
            .build()
            .toString()
        val body = apiGet(generation, "lyrics", url, token, auth.mediaUserToken) ?: return null
        val song = JSONObject(body).optJSONArray("data")?.optJSONObject(0) ?: return null
        val relationships = song.optJSONObject("relationships")
        val hadLyricData = listOf("syllable-lyrics", "lyrics").any { key ->
            (relationships?.optJSONObject(key)?.optJSONArray("data")?.length() ?: 0) > 0
        }
        return LyricsAnswer(officialTtml(song, auth.language()), hadLyricData)
    }

    /** The account storefront and the languages it supports, default first. */
    fun accountStorefront(mediaUserToken: String): Pair<String, List<String>>? {
        val token = developerToken(0L) ?: return null
        val body = apiGet(0L, "me-storefront", "$API/v1/me/storefront", token, mediaUserToken) ?: return null
        val item = JSONObject(body).optJSONArray("data")?.optJSONObject(0) ?: return null
        val attributes = item.optJSONObject("attributes")
        val default = attributes?.optString("defaultLanguageTag").orEmpty()
        val supported = attributes?.optJSONArray("supportedLanguageTags")
        val languages = buildList {
            if (default.isNotBlank()) add(default)
            if (supported != null) for (i in 0 until supported.length()) supported.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
        val storefront = AppleMusicAuth.normalizeStorefront(item.optString("id"))
        return if (storefront.isBlank()) null else storefront to languages
    }

    private fun song(item: JSONObject?): Song? {
        val id = item?.optString("id").orEmpty()
        val attributes = item?.optJSONObject("attributes") ?: return null
        val title = attributes.optString("name")
        if (id.isBlank() || title.isBlank()) return null
        val artwork = attributes.optJSONObject("artwork")?.optString("url").orEmpty()
            .replace("{w}", "300").replace("{h}", "300").replace("{f}", "jpg")
        return Song(
            id = id,
            title = title,
            artist = attributes.optString("artistName"),
            album = attributes.optString("albumName"),
            durationMs = attributes.optLong("durationInMillis"),
            coverUrl = artwork,
            hasSyncedLyrics = attributes.optBoolean("hasTimeSyncedLyrics")
        )
    }

    private fun apiGet(generation: Long, op: String, url: String, developerToken: String, userToken: String?): String? {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $developerToken")
            .header("Origin", SITE)
            .header("Referer", "$SITE/")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept", "application/json")
        if (!userToken.isNullOrBlank()) {
            builder.header("Media-User-Token", userToken)
            builder.header("Cookie", "media-user-token=$userToken")
        }
        val started = System.currentTimeMillis()
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            UniversalDiagnostics.networkCall(
                source = "apple-music/official",
                op = op,
                generation = generation,
                httpCode = response.code,
                durationMs = System.currentTimeMillis() - started,
                bodyChars = body.length,
                parseOk = response.isSuccessful && body.isNotBlank()
            )
            if (response.code == 401 || response.code == 403) {
                // The developer token is checked on every call; only a call with the user token
                // can tell that the account token itself was refused.
                if (userToken.isNullOrBlank()) {
                    AppleMusicAuthState.update { it.copy(developerToken = "", developerTokenExpiry = 0L) }
                    return null
                }
                throw Rejected()
            }
            if (!response.isSuccessful || body.isBlank()) return null
            return body
        }
    }

    private fun getText(generation: Long, op: String, url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).header("User-Agent", WEB_USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val started = System.currentTimeMillis()
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            UniversalDiagnostics.networkCall(
                source = "apple-music/official",
                op = op,
                generation = generation,
                httpCode = response.code,
                durationMs = System.currentTimeMillis() - started,
                bodyChars = body.length,
                parseOk = response.isSuccessful && body.isNotBlank()
            )
            return body.takeIf { response.isSuccessful && it.isNotBlank() }
        }
    }
}

/** Pure parsing helpers, kept outside the client so they are unit-testable on the JVM. */
internal fun findWebPlayToken(script: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): Pair<String, Long>? {
    val jwt = Regex("""(?:^|[^A-Za-z0-9_-])([A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,})(?![A-Za-z0-9_-])""")
    return jwt.findAll(script).mapNotNull { match ->
        val token = match.groupValues[1]
        val parts = token.split('.')
        val header = decodeJwtPart(parts[0]) ?: return@mapNotNull null
        val payload = decodeJwtPart(parts[1]) ?: return@mapNotNull null
        val expiry = payload.optLong("exp")
        val valid = header.optString("kid") == "WebPlayKid" &&
            header.optString("alg") == "ES256" &&
            payload.optString("iss") == "AMPWebPlay" &&
            expiry > nowSeconds
        if (valid) token to expiry else null
    }.maxByOrNull { it.second }
}

private fun decodeJwtPart(part: String): JSONObject? = runCatching {
    val bytes = java.util.Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '='))
    JSONObject(String(bytes, Charsets.UTF_8))
}.getOrNull()

/**
 * Picks the TTML from a catalogue song: syllable lyrics first, then line lyrics; a localization
 * in the request language first, then the plain document. Localizations come as a string, an
 * array of `{locale, ttml}` or a map keyed by language.
 */
internal fun officialTtml(song: JSONObject, language: String): String? {
    val relationships = song.optJSONObject("relationships") ?: return null
    for (key in listOf("syllable-lyrics", "lyrics")) {
        val attributes = relationships.optJSONObject(key)?.optJSONArray("data")
            ?.optJSONObject(0)?.optJSONObject("attributes") ?: continue
        localizedTtml(attributes.opt("ttmlLocalizations"), language)?.let { return it }
        attributes.optString("ttml").ifBlank { attributes.optString("ttmlContent") }
            .takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun localizedTtml(value: Any?, language: String): String? {
    val wanted = java.util.Locale.forLanguageTag(language).language
    fun matches(tag: String) = tag.isNotBlank() && java.util.Locale.forLanguageTag(tag).language.equals(wanted, ignoreCase = true)
    fun ttmlOf(item: Any?): String? = when (item) {
        is String -> item.takeIf { it.isNotBlank() }
        is JSONObject -> listOf("ttml", "ttmlContent", "lyrics", "value")
            .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() } }
        else -> null
    }
    return when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONArray -> {
            val items = (0 until value.length()).map { value.opt(it) }
            items.firstOrNull { item ->
                item is JSONObject && matches(listOf("locale", "language", "languageTag", "lang", "id")
                    .firstNotNullOfOrNull { k -> item.optString(k).takeIf { it.isNotBlank() } }.orEmpty())
            }?.let(::ttmlOf) ?: items.firstNotNullOfOrNull(::ttmlOf)
        }
        is JSONObject -> ttmlOf(value) ?: run {
            val keys = value.keys().asSequence().toList()
            keys.firstOrNull(::matches)?.let { ttmlOf(value.opt(it)) }
                ?: keys.firstNotNullOfOrNull { ttmlOf(value.opt(it)) }
        }
        else -> null
    }
}
