/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseRuntimeProfileTest {

    @Test
    fun resolvesExplicitRuntimeProfiles() {
        assertEquals(
            NeteaseRuntimeProfile.OFFICIAL_APPEND,
            NeteaseRuntimeProfile.resolve(
                NeteasePlayerConstants.HOST_PACKAGE,
                NeteasePlayerConstants.HOST_PACKAGE
            )
        )
        assertEquals(
            NeteaseRuntimeProfile.CONSTRUCTED,
            NeteaseRuntimeProfile.resolve(
                NeteasePlayerConstants.HOST_PACKAGE,
                NeteasePlayerConstants.NETEASE_PLAY_PROCESS
            )
        )
        assertEquals(
            NeteaseRuntimeProfile.OFFICIAL_APPEND,
            NeteaseRuntimeProfile.resolve(
                NeteasePlayerConstants.HONOR_HOST_PACKAGE,
                NeteasePlayerConstants.HONOR_HOST_PACKAGE
            )
        )
        assertEquals(
            NeteaseRuntimeProfile.OFFICIAL_APPEND,
            NeteaseRuntimeProfile.resolve(
                NeteasePlayerConstants.HONOR_HOST_PACKAGE,
                NeteasePlayerConstants.HONOR_PLAY_PROCESS
            )
        )
        assertNull(
            NeteaseRuntimeProfile.resolve(
                NeteasePlayerConstants.HOST_PACKAGE,
                "com.netease.cloudmusic:push"
            )
        )
        assertNull(
            NeteaseRuntimeProfile.resolve(
                NeteasePlayerConstants.HONOR_HOST_PACKAGE,
                "com.hihonor.cloudmusic:push"
            )
        )
        assertNull(NeteaseRuntimeProfile.resolve("com.example", "com.example"))
    }

    @Test
    fun api102EntryAcceptsOnlyTheFourProfileProcesses() {
        assertEquals(
            setOf(
                NeteasePlayerConstants.HOST_PACKAGE,
                NeteasePlayerConstants.HONOR_HOST_PACKAGE
            ),
            NeteaseProfileProcessPolicy.packages
        )
        assertTrue(
            NeteaseProfileProcessPolicy.accepts(
                NeteasePlayerConstants.HOST_PACKAGE,
                NeteasePlayerConstants.HOST_PACKAGE
            )
        )
        assertTrue(
            NeteaseProfileProcessPolicy.accepts(
                NeteasePlayerConstants.HOST_PACKAGE,
                NeteasePlayerConstants.NETEASE_PLAY_PROCESS
            )
        )
        assertTrue(
            NeteaseProfileProcessPolicy.accepts(
                NeteasePlayerConstants.HONOR_HOST_PACKAGE,
                NeteasePlayerConstants.HONOR_HOST_PACKAGE
            )
        )
        assertTrue(
            NeteaseProfileProcessPolicy.accepts(
                NeteasePlayerConstants.HONOR_HOST_PACKAGE,
                NeteasePlayerConstants.HONOR_PLAY_PROCESS
            )
        )
        assertFalse(
            NeteaseProfileProcessPolicy.accepts(
                NeteasePlayerConstants.HOST_PACKAGE,
                NeteasePlayerConstants.HOST_PACKAGE + ":push"
            )
        )
    }
}
