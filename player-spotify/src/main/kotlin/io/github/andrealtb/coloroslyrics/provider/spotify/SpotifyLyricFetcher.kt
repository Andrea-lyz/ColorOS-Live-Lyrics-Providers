/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import android.content.Context
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import kotlinx.coroutines.delay
import java.util.Locale

sealed class SpotifyFetchOutcome {
    data class Lyrics(val publication: SpotifyPublication) : SpotifyFetchOutcome()
    data object NoLyric : SpotifyFetchOutcome()
    data object HeadersMissing : SpotifyFetchOutcome()
    data object Failed : SpotifyFetchOutcome()
}

class SpotifyLyricFetcher(
    private val api: SpotifyColorLyricsApi = SpotifyColorLyricsApi(),
    private val delayMs: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun fetch(
        context: Context,
        track: TrackIdentity,
        generation: Long,
        headers: SpotifyAuthHeaderStore
    ): SpotifyFetchOutcome {
        val rawId = SpotifyTrackIdentity.rawTrackId(track.id) ?: return SpotifyFetchOutcome.NoLyric
        val language = Locale.getDefault().toLanguageTag()
        val cacheDir = context.cacheDir
        SpotifyDiskCache.get(cacheDir, language, rawId)?.let { cached ->
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "lyric",
                    event = "LYRIC_CACHE_HIT",
                    generation = generation,
                    reason = "disk"
                )
            )
            return decodedOrNoLyric(cached, track)
        }

        var unauthorizedRetries = 0
        while (true) {
            val ready = waitForHeaders(headers, generation)
            if (!ready) {
                StructuredDiagnostics.logInfo(
                    DiagnosticEvent(
                        component = SpotifyPlayerConstants.COMPONENT,
                        area = "lyric",
                        event = "LYRIC_HEADERS_MISSING",
                        generation = generation
                    )
                )
                return SpotifyFetchOutcome.HeadersMissing
            }
            when (val result = api.fetch(rawId, headers.snapshot())) {
                is SpotifyColorLyricsResult.Success -> {
                    SpotifyDiskCache.put(cacheDir, language, rawId, result.body)
                    return decodedOrNoLyric(result.body, track)
                }
                is SpotifyColorLyricsResult.NotFound -> {
                    StructuredDiagnostics.logInfo(
                        DiagnosticEvent(
                            component = SpotifyPlayerConstants.COMPONENT,
                            area = "lyric",
                            event = "NO_LYRIC",
                            generation = generation,
                            reason = "http-404"
                        )
                    )
                    return SpotifyFetchOutcome.NoLyric
                }
                is SpotifyColorLyricsResult.Forbidden -> {
                    StructuredDiagnostics.logInfo(
                        DiagnosticEvent(
                            component = SpotifyPlayerConstants.COMPONENT,
                            area = "lyric",
                            event = "LYRIC_FORBIDDEN",
                            generation = generation,
                            reason = "http-${result.code}"
                        )
                    )
                    return SpotifyFetchOutcome.Failed
                }
                is SpotifyColorLyricsResult.Unauthorized -> {
                    headers.invalidateAuthorization()
                    if (unauthorizedRetries >= SpotifyRetryPolicy.MAX_UNAUTHORIZED_RETRIES) {
                        StructuredDiagnostics.logInfo(
                            DiagnosticEvent(
                                component = SpotifyPlayerConstants.COMPONENT,
                                area = "lyric",
                                event = "LYRIC_UNAUTHORIZED",
                                generation = generation,
                                reason = "http-${result.code}"
                            )
                        )
                        return SpotifyFetchOutcome.Failed
                    }
                    unauthorizedRetries += 1
                }
                is SpotifyColorLyricsResult.Failed -> {
                    StructuredDiagnostics.logInfo(
                        DiagnosticEvent(
                            component = SpotifyPlayerConstants.COMPONENT,
                            area = "lyric",
                            event = "LYRIC_FETCH_FAILED",
                            generation = generation,
                            reason = result.reason
                        )
                    )
                    return SpotifyFetchOutcome.Failed
                }
            }
        }
    }

    private fun decodedOrNoLyric(body: String, track: TrackIdentity): SpotifyFetchOutcome {
        val publication = SpotifyLyricDecoder.decode(body, track)
        return if (publication != null) {
            SpotifyFetchOutcome.Lyrics(publication)
        } else {
            SpotifyFetchOutcome.NoLyric
        }
    }

    private suspend fun waitForHeaders(
        headers: SpotifyAuthHeaderStore,
        generation: Long
    ): Boolean {
        if (headers.hasRequired()) return true
        var attempt = 0
        while (true) {
            val delay = SpotifyRetryPolicy.nextHeaderWaitDelayMs(attempt) ?: return headers.hasRequired()
            StructuredDiagnostics.logDebug(
                DiagnosticEvent(
                    component = SpotifyPlayerConstants.COMPONENT,
                    area = "lyric",
                    event = "LYRIC_HEADERS_WAIT",
                    generation = generation,
                    reason = "attempt=$attempt delayMs=$delay keys=" +
                        SpotifyAuthHeaderPolicy.capturedKeyList(headers.snapshot())
                )
            )
            delayMs(delay)
            if (headers.hasRequired()) return true
            attempt += 1
        }
    }
}
