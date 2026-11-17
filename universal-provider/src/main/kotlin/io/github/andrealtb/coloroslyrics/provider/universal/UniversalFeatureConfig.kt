package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.Context
import android.content.Intent
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuth
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuthState
import org.json.JSONObject
import java.io.File

/**
 * Lyric features configured from the app and applied where lyrics are fetched: the TTML-first
 * switch and the official Apple Music credentials.
 *
 * The app keeps its copy in private preferences ([UniversalFeaturePrefs]); system_server keeps
 * its own in a `/data/system` file that only system_server can read. The update broadcast is sent
 * to the `android` package only, because it can carry the account's media-user-token.
 */
internal object UniversalFeatureConfig {
    const val ACTION_UPDATE = "io.github.andrealtb.coloroslyrics.provider.universal.ACTION_UPDATE_FEATURE_CONFIG"
    const val EXTRA_TTML_FIRST = "extra_ttml_first"
    const val EXTRA_AM_AUTH = "extra_am_auth"
    /** Snapshot key: `missing`, `ok` or `rejected` (never the token itself). */
    const val SNAPSHOT_AM_STATUS = "amAuthStatus"

    private const val FILE = "/data/system/universal_features.json"

    @Volatile
    var ttmlFirst: Boolean = false
        private set

    @Volatile
    private var loaded = false

    /** system_server only: reads the stored config once and routes Apple Music state to it. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val root = runCatching { JSONObject(File(FILE).readText()) }.getOrNull()
            ttmlFirst = root?.optBoolean("ttmlFirst") ?: false
            val auth = AppleMusicAuth.fromJson(root?.optJSONObject("appleMusic"))
            AppleMusicAuthState.loader = { auth }
            AppleMusicAuthState.saver = { persist() }
            AppleMusicAuthState.invalidate()
            loaded = true
        }
    }

    /** Applies an update broadcast; returns true when the TTML-first switch changed. */
    fun consume(intent: Intent): Boolean {
        ensureLoaded()
        var ttmlChanged = false
        if (intent.hasExtra(EXTRA_TTML_FIRST)) {
            val value = intent.getBooleanExtra(EXTRA_TTML_FIRST, ttmlFirst)
            ttmlChanged = value != ttmlFirst
            ttmlFirst = value
        }
        intent.getStringExtra(EXTRA_AM_AUTH)?.let { encoded ->
            val incoming = AppleMusicAuth.fromJson(runCatching { JSONObject(encoded) }.getOrNull())
            val previous = AppleMusicAuthState.current
            // The developer token is public and either side may have the fresher one.
            val merged = if (incoming.developerTokenExpiry >= previous.developerTokenExpiry) incoming
            else incoming.copy(developerToken = previous.developerToken, developerTokenExpiry = previous.developerTokenExpiry)
            AppleMusicAuthState.set(merged, persist = false)
        }
        persist()
        return ttmlChanged
    }

    fun snapshotLines(): String {
        ensureLoaded()
        val auth = AppleMusicAuthState.current
        val status = if (!auth.official) "off" else if (auth.mediaUserToken.isBlank()) AppleMusicAuth.STATUS_MISSING else auth.status
        return "$SNAPSHOT_AM_STATUS=$status\n"
    }

    private fun persist() {
        runCatching {
            val root = JSONObject().apply {
                put("ttmlFirst", ttmlFirst)
                put("appleMusic", AppleMusicAuthState.current.toJson())
            }
            val target = File(FILE)
            val temp = File("$FILE.tmp")
            temp.writeText(root.toString())
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            if (!temp.renameTo(target)) target.writeText(root.toString())
        }
    }
}

/** App-process side of [UniversalFeatureConfig]. */
internal object UniversalFeaturePrefs {
    private const val PREFS = "universal_features"
    private const val KEY_TTML_FIRST = "ttml_first"
    private const val KEY_AM_AUTH = "apple_music_auth"

    @Volatile
    private var appContext: Context? = null

    /** Called from Application.onCreate; Apple Music state is read lazily on first use. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        AppleMusicAuthState.loader = {
            AppleMusicAuth.fromJson(
                runCatching { JSONObject(prefs(app).getString(KEY_AM_AUTH, null) ?: "{}") }.getOrNull()
            )
        }
        AppleMusicAuthState.saver = { auth -> prefs(app).edit().putString(KEY_AM_AUTH, auth.toJson().toString()).apply() }
    }

    fun ttmlFirst(context: Context): Boolean = prefs(context).getBoolean(KEY_TTML_FIRST, false)

    fun setTtmlFirst(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TTML_FIRST, enabled).apply()
        pushToSystem(context)
    }

    fun updateAppleMusic(context: Context, transform: (AppleMusicAuth) -> AppleMusicAuth) {
        AppleMusicAuthState.update(transform)
        pushToSystem(context)
    }

    /** Follows the status system_server reports; a rejection there means the token expired. */
    fun onSnapshotStatus(status: String?) {
        if (status != AppleMusicAuth.STATUS_REJECTED && status != AppleMusicAuth.STATUS_NO_SUBSCRIPTION) return
        val current = AppleMusicAuthState.current
        if (current.official && current.mediaUserToken.isNotBlank() && current.status != status) {
            AppleMusicAuthState.update { it.copy(status = status) }
        }
    }

    fun pushToSystem(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(Intent(UniversalFeatureConfig.ACTION_UPDATE).apply {
            // Only system_server may receive the account token.
            setPackage("android")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(UniversalFeatureConfig.EXTRA_TTML_FIRST, ttmlFirst(app))
            putExtra(UniversalFeatureConfig.EXTRA_AM_AUTH, AppleMusicAuthState.current.toJson().toString())
        })
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
