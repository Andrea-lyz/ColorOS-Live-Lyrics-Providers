/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.mode

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeModeResolverTest {

    @Before
    fun setUp() {
        RuntimeModeResolver.resetForTesting()
    }

    @Test
    fun resolvesToUnknownAndFailsClosedWhenNoMarkersPresent() {
        val resolution = RuntimeModeResolver.resolve(null)
        assertEquals(RuntimeMode.UNKNOWN, resolution.mode)
        assertFalse(resolution.mode.isSupported)
    }

    @Test
    fun resolvesToRootModuleWhenXposedHookActive() {
        RuntimeModeResolver.notifyXposedHookActive()
        val resolution = RuntimeModeResolver.resolve(null)
        assertEquals(RuntimeMode.ROOT_MODULE, resolution.mode)
        assertTrue(resolution.mode.isSupported)
        assertEquals("xposed:hook-active", resolution.markerSource)
    }

    @Test
    fun resolvesNpatchFromManifestOrResourceMarker() {
        val manifest = RuntimeModeResolver.evaluateSignals(
            RuntimeModeSignals("com.test.player", "com.test.player", manifestNpatchMarker = true)
        )
        assertEquals(RuntimeMode.NPATCH_EMBEDDED, manifest.mode)
        assertTrue(manifest.markerSource.startsWith("manifest:"))

        val resource = RuntimeModeResolver.evaluateSignals(
            RuntimeModeSignals(
                "com.test.player",
                "com.test.player",
                resourceNpatchMarker = "coloros-live-lyrics-provider-v5"
            )
        )
        assertEquals(RuntimeMode.NPATCH_EMBEDDED, resource.mode)
        assertTrue(resource.markerSource.startsWith("resource:"))
    }

    @Test
    fun simultaneousRootAndNpatchSignalsFailClosed() {
        val resolution = RuntimeModeResolver.evaluateSignals(
            RuntimeModeSignals(
                "com.test.player",
                "com.test.player",
                xposedActive = true,
                manifestNpatchMarker = true
            )
        )

        assertEquals(RuntimeMode.UNKNOWN, resolution.mode)
        assertFalse(resolution.mode.isSupported)
        assertEquals("conflict:xposed+npatch", resolution.markerSource)
    }
}
