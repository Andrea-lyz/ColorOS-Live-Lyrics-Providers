/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object SpotifyAuthHeaderPolicy {
    val REQUIRED_KEYS = arrayOf(
        "authorization",
        "client-token",
        "user-agent",
        "x-client-id"
    )

    fun ingest(raw: Any?, sink: MutableMap<String, String>) {
        val pairs = flattenPairs(raw) ?: return
        var index = 0
        while (index + 1 < pairs.size) {
            ingest(pairs[index], pairs[index + 1], sink)
            index += 2
        }
    }

    fun ingest(name: String?, value: String?, sink: MutableMap<String, String>) {
        val key = name?.lowercase(Locale.ENGLISH) ?: return
        if (key in REQUIRED_KEYS && !value.isNullOrBlank()) {
            sink[key] = value
        }
    }

    fun hasRequired(headers: Map<String, String>): Boolean =
        REQUIRED_KEYS.all { key -> !headers[key].isNullOrBlank() }

    fun capturedKeyList(headers: Map<String, String>): String =
        REQUIRED_KEYS.filter { !headers[it].isNullOrBlank() }.joinToString(",")

    fun invalidateAuthorization(headers: MutableMap<String, String>) {
        headers.remove("authorization")
        headers.remove("client-token")
    }

    private fun flattenPairs(raw: Any?): List<String>? {
        val values = when (raw) {
            is Array<*> -> raw.mapNotNull { it as? String }
            is List<*> -> raw.mapNotNull { it as? String }
            else -> return null
        }
        return values.takeIf { it.size >= 2 }
    }
}

class SpotifyAuthHeaderStore {
    private val headers = ConcurrentHashMap<String, String>()

    /** @return true when this ingest is the transition from incomplete to complete. */
    fun ingest(raw: Any?): Boolean {
        val wasReady = hasRequired()
        SpotifyAuthHeaderPolicy.ingest(raw, headers)
        return !wasReady && hasRequired()
    }

    fun ingest(name: String?, value: String?): Boolean {
        val wasReady = hasRequired()
        SpotifyAuthHeaderPolicy.ingest(name, value, headers)
        return !wasReady && hasRequired()
    }

    fun snapshot(): Map<String, String> = HashMap(headers)

    fun capturedKeyList(): String = SpotifyAuthHeaderPolicy.capturedKeyList(headers)

    fun hasRequired(): Boolean = SpotifyAuthHeaderPolicy.hasRequired(headers)

    fun invalidateAuthorization() = SpotifyAuthHeaderPolicy.invalidateAuthorization(headers)
}
