/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SequentialProviderFetcherTest {
    @Test
    fun timesOutOneProviderThenKeepsConfiguredOrder() = runBlocking {
        val attempted = mutableListOf<String>()
        val timedOut = mutableListOf<String>()

        val hit = SequentialProviderFetcher.firstUsable(
            providers = listOf("slow", "empty", "preferred", "unused"),
            timeoutMillis = 10,
            fetch = { provider ->
                attempted += provider
                when (provider) {
                    "slow" -> awaitCancellation()
                    "empty" -> ""
                    else -> "lyrics:$provider"
                }
            },
            isUsable = String::isNotEmpty,
            onTimeout = timedOut::add
        )

        assertEquals(listOf("slow", "empty", "preferred"), attempted)
        assertEquals(listOf("slow"), timedOut)
        assertEquals("preferred", hit?.provider)
        assertEquals("lyrics:preferred", hit?.value)
    }
}
