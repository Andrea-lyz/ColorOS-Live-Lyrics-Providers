/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class SpotifyColorLyricsResult {
    data class Success(val body: String) : SpotifyColorLyricsResult()
    data class NotFound(val trackId: String) : SpotifyColorLyricsResult()
    data class Unauthorized(val code: Int) : SpotifyColorLyricsResult()
    data class Forbidden(val code: Int) : SpotifyColorLyricsResult()
    data class Failed(val reason: String) : SpotifyColorLyricsResult()
}

class SpotifyColorLyricsApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch(rawTrackId: String, headers: Map<String, String>): SpotifyColorLyricsResult {
        if (rawTrackId.isBlank()) return SpotifyColorLyricsResult.Failed("blank-id")
        if (!SpotifyAuthHeaderPolicy.hasRequired(headers)) {
            return SpotifyColorLyricsResult.Failed("headers-missing")
        }
        val url = SpotifyPlayerConstants.COLOR_LYRICS_BASE_URL + rawTrackId +
            "?vocalRemoval=false&clientLanguage=" + Locale.getDefault().toLanguageTag() +
            "&preview=false"
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("accept", "application/json")
            .addHeader("app-platform", "WebPlayer")
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        return try {
            execute(requestBuilder.build()).use { response ->
                val code = response.code
                val body = response.body.string()
                when {
                    code == 404 -> SpotifyColorLyricsResult.NotFound(rawTrackId)
                    code == 401 -> SpotifyColorLyricsResult.Unauthorized(code)
                    code == 403 -> SpotifyColorLyricsResult.Forbidden(code)
                    !response.isSuccessful ->
                        SpotifyColorLyricsResult.Failed("http-$code")
                    !isJsonObject(body) -> SpotifyColorLyricsResult.Failed("invalid-json")
                    else -> SpotifyColorLyricsResult.Success(body)
                }
            }
        } catch (error: IOException) {
            SpotifyColorLyricsResult.Failed(error.javaClass.simpleName)
        }
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }

    private fun isJsonObject(body: String): Boolean = runCatching {
        JSONObject(body)
        true
    }.getOrDefault(false)
}
