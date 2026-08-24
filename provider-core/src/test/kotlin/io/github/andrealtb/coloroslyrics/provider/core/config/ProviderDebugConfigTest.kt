/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDebugConfigTest {

    @Test
    fun registryContainsEveryMigrationProviderExactlyOnce() {
        assertEquals(
            setOf(
                "netease", "qq", "qqhd", "kugou", "kuwo", "spotify", "lx", "poweramp",
                "salt", "cone", "qishui", "musicfree", "gramophone", "symfonium", "metrolist", "apple"
            ),
            ProviderId.entries.map { it.configKey }.toSet()
        )
        assertEquals(16, ProviderId.entries.size)
    }

    @Test
    fun everyProviderDefaultsClosedInEveryRuntimeMode() {
        RuntimeMode.entries.forEach { mode ->
            ProviderId.entries.forEach { provider ->
                assertFalse(ProviderDebugConfig.resolve(mode, provider))
            }
        }
    }

    @Test
    fun rootAndEmbeddedSourcesAreSelectedWithoutCrossReading() {
        val rootSource = MapSource(mapOf(ProviderId.SALT to true))
        val embeddedSource = MapSource(mapOf(ProviderId.CONE to true))

        assertTrue(
            ProviderDebugConfig.resolve(RuntimeMode.ROOT_MODULE, ProviderId.SALT, rootSource, embeddedSource)
        )
        assertFalse(
            ProviderDebugConfig.resolve(RuntimeMode.NPATCH_EMBEDDED, ProviderId.SALT, rootSource, embeddedSource)
        )
        assertEquals(1, rootSource.readCount)
        assertEquals(1, embeddedSource.readCount)
    }

    @Test
    fun providerSettingsRemainIndependent() {
        val rootSource = MapSource(mapOf(ProviderId.SALT to true, ProviderId.APPLE to false))
        assertTrue(ProviderDebugConfig.resolve(RuntimeMode.ROOT_MODULE, ProviderId.SALT, rootSource))
        assertFalse(ProviderDebugConfig.resolve(RuntimeMode.ROOT_MODULE, ProviderId.APPLE, rootSource))
        assertFalse(ProviderDebugConfig.resolve(RuntimeMode.ROOT_MODULE, ProviderId.NETEASE, rootSource))
    }

    @Test
    fun embeddedMarkersEnableDebugOnlyWhenAtLeastOneIsTrueAndUnknownFailsClosed() {
        assertTrue(ProviderDebugConfig.resolveEmbeddedMarker(false, true))
        assertTrue(ProviderDebugConfig.resolveEmbeddedMarker(true, false))
        assertFalse(ProviderDebugConfig.resolveEmbeddedMarker(false, false))
        assertFalse(ProviderDebugConfig.resolveEmbeddedMarker(null, null))

        val throwing = ProviderDebugSource { error("unavailable") }
        assertFalse(ProviderDebugConfig.resolve(RuntimeMode.UNKNOWN, ProviderId.SALT, throwing, throwing))
        assertFalse(ProviderDebugConfig.resolve(RuntimeMode.ROOT_MODULE, ProviderId.SALT, throwing, throwing))
    }

    private class MapSource(private val values: Map<ProviderId, Boolean>) : ProviderDebugSource {
        var readCount = 0

        override fun read(provider: ProviderId): Boolean? {
            readCount++
            return values[provider]
        }
    }
}
