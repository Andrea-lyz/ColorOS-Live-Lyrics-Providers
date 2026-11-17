package io.github.andrealtb.coloroslyrics.provider.universal.api

import org.json.JSONObject
import java.util.Locale

/**
 * Settings and credentials of the official Apple Music lyric mode.
 *
 * The official catalogue only serves lyrics to a signed-in subscriber, so the request carries the
 * user's own `media-user-token` (captured from the in-app music.apple.com sign-in) next to the
 * public WebPlay developer token. Neither token is ever logged.
 *
 * Storefront and language are separate identifiers: the storefront is a catalogue region code
 * (`cn`, `us`, `jp`, ...) that also scopes song ids, and the language is a BCP-47 tag the
 * storefront must support (`zh-Hans-CN`, `en-US`, ...). The account storefront wins over the
 * device region because the token only unlocks lyrics in the account's own catalogue.
 */
data class AppleMusicAuth(
    val official: Boolean = false,
    val mediaUserToken: String = "",
    /** User override; blank follows the account storefront. */
    val storefrontOverride: String = "",
    /** Storefront of the signed-in account (`/v1/me/storefront`), blank until known. */
    val accountStorefront: String = "",
    /** Languages the account storefront supports, default first. */
    val storefrontLanguages: List<String> = emptyList(),
    val developerToken: String = "",
    /** Expiry of [developerToken], epoch seconds. */
    val developerTokenExpiry: Long = 0L,
    val status: String = STATUS_MISSING,
    /** When [mediaUserToken] was last captured or confirmed, epoch millis. */
    val tokenUpdatedAt: Long = 0L
) {
    val usable: Boolean
        get() = official && mediaUserToken.isNotBlank() &&
            status != STATUS_REJECTED && status != STATUS_NO_SUBSCRIPTION

    fun storefront(locale: Locale = Locale.getDefault()): String =
        normalizeStorefront(storefrontOverride)
            .ifBlank { normalizeStorefront(accountStorefront) }
            .ifBlank { normalizeStorefront(locale.country) }
            .ifBlank { "us" }

    /**
     * The `l` parameter: the supported tag closest to the device locale. An override storefront
     * has no known language list, so the device tag is sent and Apple falls back to the
     * storefront default when it does not support it.
     */
    fun language(locale: Locale = Locale.getDefault()): String {
        val languages = if (storefrontOverride.isBlank()) storefrontLanguages else emptyList()
        if (languages.isEmpty()) return appleLanguageTag(locale)
        val wanted = Locale.forLanguageTag(appleLanguageTag(locale))
        return languages.maxByOrNull { tag ->
            val candidate = Locale.forLanguageTag(tag)
            when {
                !candidate.language.equals(wanted.language, ignoreCase = true) -> 0
                candidate.script.equals(wanted.script, ignoreCase = true) &&
                    candidate.country.equals(wanted.country, ignoreCase = true) -> 3
                candidate.script.isNotEmpty() && candidate.script.equals(wanted.script, ignoreCase = true) -> 2
                else -> 1
            }
        }?.takeIf { Locale.forLanguageTag(it).language.equals(wanted.language, ignoreCase = true) }
            ?: languages.first()
    }

    fun developerTokenValid(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        developerToken.isNotBlank() && developerTokenExpiry - 60L > nowSeconds

    fun toJson(): JSONObject = JSONObject().apply {
        put("official", official)
        put("mediaUserToken", mediaUserToken)
        put("storefrontOverride", storefrontOverride)
        put("accountStorefront", accountStorefront)
        put("storefrontLanguages", org.json.JSONArray(storefrontLanguages))
        put("developerToken", developerToken)
        put("developerTokenExpiry", developerTokenExpiry)
        put("status", status)
        put("tokenUpdatedAt", tokenUpdatedAt)
    }

    companion object {
        const val STATUS_MISSING = "missing"
        const val STATUS_OK = "ok"
        const val STATUS_REJECTED = "rejected"
        /** The token works (the account answers) but Apple withholds lyrics: no subscription. */
        const val STATUS_NO_SUBSCRIPTION = "noSubscription"

        /** Storefronts offered in settings; any other two-letter code is still accepted. */
        val STOREFRONTS = listOf("cn", "hk", "tw", "jp", "kr", "us", "gb", "sg", "tr", "au", "ca")

        fun fromJson(json: JSONObject?): AppleMusicAuth {
            if (json == null) return AppleMusicAuth()
            val languages = json.optJSONArray("storefrontLanguages")
            return AppleMusicAuth(
                official = json.optBoolean("official"),
                mediaUserToken = json.optString("mediaUserToken"),
                storefrontOverride = json.optString("storefrontOverride"),
                accountStorefront = json.optString("accountStorefront"),
                storefrontLanguages = if (languages == null) emptyList()
                else (0 until languages.length()).map { languages.optString(it) }.filter { it.isNotBlank() },
                developerToken = json.optString("developerToken"),
                developerTokenExpiry = json.optLong("developerTokenExpiry"),
                status = json.optString("status").ifBlank { STATUS_MISSING },
                tokenUpdatedAt = json.optLong("tokenUpdatedAt")
            )
        }

        /** Region code of a storefront; accepts `CN`, `zh-CN` style and rejects anything else. */
        fun normalizeStorefront(value: String?): String {
            val text = value.orEmpty().trim()
            if (text.isEmpty()) return ""
            val region = if (text.contains('-') || text.contains('_')) {
                Locale.forLanguageTag(text.replace('_', '-')).country
            } else {
                text
            }
            return region.lowercase(Locale.ROOT).takeIf { it.length == 2 && it.all(Char::isLetter) }.orEmpty()
        }

        /** Apple writes Chinese with a script subtag: zh-CN is zh-Hans-CN, zh-TW is zh-Hant-TW. */
        fun appleLanguageTag(locale: Locale): String {
            if (!locale.language.equals("zh", ignoreCase = true)) return locale.toLanguageTag()
            val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
            val script = if (locale.script.isNotEmpty()) locale.script else if (traditional) "Hant" else "Hans"
            val country = locale.country.ifEmpty { if (traditional) "TW" else "CN" }
            return "zh-$script-$country"
        }
    }
}

/**
 * Process-wide holder of [AppleMusicAuth]. Each process installs a [loader] for its own storage
 * (system_server: a `/data/system` file, the app: its preferences) and a [saver] for changes the
 * source makes itself (a refreshed developer token, a rejected user token).
 */
object AppleMusicAuthState {
    @Volatile
    var loader: () -> AppleMusicAuth = { AppleMusicAuth() }

    @Volatile
    var saver: (AppleMusicAuth) -> Unit = {}

    @Volatile
    private var cached: AppleMusicAuth? = null

    val current: AppleMusicAuth
        get() = cached ?: synchronized(this) { cached ?: loader().also { cached = it } }

    fun set(auth: AppleMusicAuth, persist: Boolean = true) {
        synchronized(this) { cached = auth }
        if (persist) runCatching { saver(auth) }
    }

    fun update(transform: (AppleMusicAuth) -> AppleMusicAuth) {
        val next = synchronized(this) { transform(current).also { cached = it } }
        runCatching { saver(next) }
    }

    /** Forgets the cached value so the next read goes back to the [loader]. */
    fun invalidate() {
        synchronized(this) { cached = null }
    }
}
