/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlayerConstantsTest {
    @Test
    fun moduleQualifiesOfficialNeteaseAndHonorHosts() {
        assertEquals(
            "io.github.andrealtb.coloroslyrics.provider.netease",
            NeteasePlayerConstants.MODULE_PACKAGE
        )
        assertEquals("com.netease.cloudmusic", NeteasePlayerConstants.HOST_PACKAGE)
        assertEquals(
            "com.netease.cloudmusic:play",
            NeteasePlayerConstants.NETEASE_PLAY_PROCESS
        )
        assertEquals("netease-official-append", NeteasePayloadMode.OFFICIAL_APPEND.source)
        assertEquals("netease-constructed", NeteasePayloadMode.CONSTRUCTED.source)
        assertEquals(
            listOf("com.netease.cloudmusic", "com.hihonor.cloudmusic"),
            NeteasePlayerConstants.QUALIFIED_HOST_PACKAGES.toList()
        )
        assertEquals(
            "com.hihonor.cloudmusic:play",
            NeteasePlayerConstants.HONOR_PLAY_PROCESS
        )
        assertEquals(16, NeteasePlayerConstants.LYRIC_HANDLER_WHAT)
        assertTrue(
            NeteasePlayerConstants.QUALIFIED_HOST_PACKAGES.contains(
                NeteasePlayerConstants.HONOR_HOST_PACKAGE
            )
        )
    }
}
