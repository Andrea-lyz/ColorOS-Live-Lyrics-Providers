/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyAuthHeaderPolicyTest {
    @Test
    fun capturesRequiredKeysFromNameValueArray() {
        val sink = mutableMapOf<String, String>()
        SpotifyAuthHeaderPolicy.ingest(
            arrayOf(
                "Authorization",
                "Bearer token",
                "client-token",
                "client",
                "User-Agent",
                "Spotify/9.1",
                "x-client-id",
                "abc",
                "accept",
                "application/json"
            ),
            sink
        )
        assertTrue(SpotifyAuthHeaderPolicy.hasRequired(sink))
        assertEquals("Bearer token", sink["authorization"])
        assertEquals("authorization,client-token,user-agent,x-client-id",
            SpotifyAuthHeaderPolicy.capturedKeyList(sink))
        SpotifyAuthHeaderPolicy.invalidateAuthorization(sink)
        assertFalse(SpotifyAuthHeaderPolicy.hasRequired(sink))
        assertFalse(sink.containsKey("authorization"))
        assertTrue(sink.containsKey("user-agent"))
    }

    @Test
    fun storeReportsTheTransitionToCompleteHeaders() {
        val store = SpotifyAuthHeaderStore()
        assertFalse(
            store.ingest(
                arrayOf("authorization", "Bearer token", "client-token", "client")
            )
        )
        assertTrue(
            store.ingest(
                arrayOf(
                    "User-Agent",
                    "Spotify/9.1",
                    "x-client-id",
                    "abc"
                )
            )
        )
        assertFalse(
            store.ingest(
                arrayOf("authorization", "Bearer next")
            )
        )
        assertEquals(
            "authorization,client-token,user-agent,x-client-id",
            store.capturedKeyList()
        )
    }

    @Test
    fun storeCapturesCronetHeaderPairsWithoutLoggingValues() {
        val store = SpotifyAuthHeaderStore()
        assertFalse(store.ingest("Authorization", "Bearer token"))
        assertFalse(store.ingest("client-token", "client"))
        assertFalse(store.ingest("User-Agent", "Spotify/9.1"))
        assertTrue(store.ingest("x-client-id", "abc"))
        assertEquals(
            "authorization,client-token,user-agent,x-client-id",
            store.capturedKeyList()
        )
    }

    @Test
    fun incompleteHeadersAreNotReady() {
        assertFalse(
            SpotifyAuthHeaderPolicy.hasRequired(mapOf("authorization" to "Bearer x"))
        )
    }
}
