package io.github.andrealtb.coloroslyrics.provider.universal.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.andrealtb.coloroslyrics.provider.universal.R
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalAppIo
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalFeaturePrefs
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuth
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuthState
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicOfficialClient
import io.github.andrealtb.coloroslyrics.provider.universal.api.findWebPlayToken
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reading the account token out of the music.apple.com web player.
 *
 * Signing in happens on Apple's own page inside a WebView; the app never sees the password. Once
 * signed in, the page holds a `media-user-token` cookie and sends the same value as a request
 * header to `amp-api`, so either is read. The WebView keeps its cookies, which is what lets
 * [AppleMusicTokenRefresher] renew the token later without asking again.
 */
internal object AppleMusicWebToken {
    const val SITE = "https://music.apple.com"
    /** The desktop player shows the sign-in button; the mobile site pushes to the app instead. */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun cookieToken(): String? {
        val manager = CookieManager.getInstance()
        return listOf(SITE, "https://amp-api.music.apple.com", "https://apple.com")
            .firstNotNullOfOrNull { url -> parse(manager.getCookie(url)) }
    }

    fun parse(cookies: String?): String? = cookies.orEmpty().split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("media-user-token=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.length > 20 }

    /** Reads both tokens from a player request, if it is an `amp-api` call carrying them. */
    fun fromRequest(request: WebResourceRequest): Pair<String?, Pair<String, Long>?> {
        if (request.url.host != "amp-api.music.apple.com") return null to null
        val headers = request.requestHeaders.mapKeys { it.key.lowercase(Locale.ROOT) }
        val user = headers["media-user-token"]?.trim()?.takeIf { it.length > 20 }
        val developer = headers["authorization"]?.removePrefix("Bearer ")?.trim()?.let(::findWebPlayToken)
        return user to developer
    }

    fun clearCookies() {
        val manager = CookieManager.getInstance()
        listOf(SITE, "https://amp-api.music.apple.com").forEach { url ->
            manager.getCookie(url).orEmpty().split(';').map { it.substringBefore('=').trim() }
                .filter { it.isNotEmpty() }
                .forEach { name -> manager.setCookie(url, "$name=; Max-Age=0") }
        }
        manager.flush()
    }

    sealed interface Verification {
        data class Ok(val storefront: String, val languages: List<String>) : Verification
        /** Apple answered and refused the token. */
        object Rejected : Verification
        /** No answer (offline, timeout, page changed): says nothing about the token. */
        object Unknown : Verification
    }

    fun verify(token: String): Verification = try {
        AppleMusicOfficialClient(OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build())
            .accountStorefront(token)
            ?.let { Verification.Ok(it.first, it.second) }
            ?: Verification.Unknown
    } catch (_: AppleMusicOfficialClient.Rejected) {
        Verification.Rejected
    } catch (_: Exception) {
        Verification.Unknown
    }

    fun startUrl(): String {
        val storefront = AppleMusicAuthState.current.storefront()
        return "$SITE/$storefront/home"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }
}

class AppleMusicLoginActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var status: TextView
    @Volatile private var headerToken: String? = null
    @Volatile private var developerToken: Pair<String, Long>? = null
    private var finishing = false

    private val poll = object : Runnable {
        override fun run() {
            if (finishing) return
            val token = headerToken ?: AppleMusicWebToken.cookieToken()
            if (token != null) onToken(token) else handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = getString(R.string.am_login_hint)
            setPadding(32, 24, 32, 24)
            textSize = 14f
        }
        val close = Button(this).apply {
            text = getString(R.string.am_login_close)
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        webView = WebView(this)
        AppleMusicWebToken.configure(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val (user, developer) = AppleMusicWebToken.fromRequest(request)
                if (developer != null) developerToken = developer
                if (user != null) headerToken = user
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, 1_000L)
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        webView.loadUrl(AppleMusicWebToken.startUrl())
    }

    private fun onToken(token: String) {
        finishing = true
        status.text = getString(R.string.am_login_verifying)
        val app = applicationContext
        val developer = developerToken
        CookieManager.getInstance().flush()
        UniversalAppIo.execute {
            if (developer != null) {
                AppleMusicAuthState.update { it.copy(developerToken = developer.first, developerTokenExpiry = developer.second) }
            }
            val verification = AppleMusicWebToken.verify(token)
            val verified = verification as? AppleMusicWebToken.Verification.Ok
            UniversalFeaturePrefs.updateAppleMusic(app) {
                it.copy(
                    official = true,
                    mediaUserToken = token,
                    accountStorefront = verified?.storefront ?: it.accountStorefront,
                    storefrontLanguages = verified?.languages ?: it.storefrontLanguages,
                    status = if (verification == AppleMusicWebToken.Verification.Rejected) {
                        AppleMusicAuth.STATUS_REJECTED
                    } else {
                        AppleMusicAuth.STATUS_OK
                    },
                    tokenUpdatedAt = System.currentTimeMillis()
                )
            }
            handler.post {
                Toast.makeText(
                    app,
                    when (verification) {
                        is AppleMusicWebToken.Verification.Ok ->
                            app.getString(R.string.am_login_done, verification.storefront.uppercase(Locale.ROOT))
                        AppleMusicWebToken.Verification.Rejected -> app.getString(R.string.am_login_rejected)
                        AppleMusicWebToken.Verification.Unknown -> app.getString(R.string.am_login_unverified)
                    },
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}

/**
 * Renews the account token from the WebView's saved music.apple.com session: loading the player
 * again makes Apple re-issue the cookie while the session is alive. Runs only from the app UI,
 * at most once an hour, and only when the token is old or system_server reported it refused.
 */
internal object AppleMusicTokenRefresher {
    private const val STALE_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    private const val MIN_INTERVAL_MS = 60L * 60 * 1000
    private const val TIMEOUT_MS = 25_000L

    @Volatile private var lastAttempt = 0L
    @Volatile private var running = false

    fun refreshIfNeeded(context: Context, force: Boolean = false) {
        val auth = AppleMusicAuthState.current
        if (!auth.official || auth.mediaUserToken.isBlank()) return
        val now = System.currentTimeMillis()
        val due = auth.status == AppleMusicAuth.STATUS_REJECTED || now - auth.tokenUpdatedAt > STALE_AFTER_MS
        if (!due || running || (!force && now - lastAttempt < MIN_INTERVAL_MS)) return
        lastAttempt = now
        running = true
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val webView = runCatching { WebView(app) }.getOrNull()
            if (webView == null) {
                running = false
                return@post
            }
            AppleMusicWebToken.configure(webView)
            var done = false
            fun finish(token: String?) {
                if (done) return
                done = true
                webView.stopLoading()
                webView.destroy()
                UniversalAppIo.execute {
                    try {
                        if (token != null) apply(app, token)
                    } finally {
                        running = false
                    }
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val (user, _) = AppleMusicWebToken.fromRequest(request)
                    if (user != null) handler.post { finish(user) }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // Let the player run its own requests first; they refresh the cookie.
                    handler.postDelayed({ finish(AppleMusicWebToken.cookieToken()) }, 6_000L)
                }
            }
            handler.postDelayed({ finish(AppleMusicWebToken.cookieToken()) }, TIMEOUT_MS)
            webView.loadUrl(AppleMusicWebToken.startUrl())
        }
    }

    private fun apply(context: Context, token: String) {
        when (val verification = AppleMusicWebToken.verify(token)) {
            is AppleMusicWebToken.Verification.Ok -> UniversalFeaturePrefs.updateAppleMusic(context) {
                it.copy(
                    mediaUserToken = token,
                    accountStorefront = verification.storefront,
                    storefrontLanguages = verification.languages,
                    status = AppleMusicAuth.STATUS_OK,
                    tokenUpdatedAt = System.currentTimeMillis()
                )
            }
            AppleMusicWebToken.Verification.Rejected -> UniversalFeaturePrefs.updateAppleMusic(context) {
                it.copy(status = AppleMusicAuth.STATUS_REJECTED)
            }
            // Offline: keep everything as it is and try again on a later visit.
            AppleMusicWebToken.Verification.Unknown -> Unit
        }
    }
}
