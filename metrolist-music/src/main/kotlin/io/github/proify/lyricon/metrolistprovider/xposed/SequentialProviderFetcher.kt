/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Runs enabled lyric providers sequentially with an independent timeout for each provider. */
internal object SequentialProviderFetcher {
    data class Hit<T>(val provider: String, val value: T)

    suspend fun <T : Any> firstUsable(
        providers: List<String>,
        timeoutMillis: Long,
        fetch: suspend (String) -> T?,
        isUsable: (T) -> Boolean = { true },
        onTimeout: (String) -> Unit = {},
        onFailure: (String, Exception) -> Unit = { _, _ -> }
    ): Hit<T>? {
        for (provider in providers) {
            try {
                var completed = false
                val value = withTimeoutOrNull(timeoutMillis) {
                    fetch(provider).also { completed = true }
                }
                if (!completed) onTimeout(provider)
                if (value != null && isUsable(value)) return Hit(provider, value)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(provider, error)
            }
        }
        return null
    }
}
