/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConePlayerConstantsTest {

    @Test
    fun constants_haveExpectedValues() {
        assertEquals("ink.trantor.coneplayer", ConePlayerConstants.CONE_PACKAGE)
        assertEquals("ink.trantor.coneplayer.gp", ConePlayerConstants.CONE_GP_PACKAGE)
        assertEquals("ink.trantor.android.mediaplayer.action.CURRENT_LYRIC_CHANGED", ConePlayerConstants.ACTION_CURRENT_LYRIC_CHANGED)
        assertEquals("extra_lyric_text", ConePlayerConstants.EXTRA_LYRIC_TEXT)
        assertEquals("ink.trantor.android.mediaplayer.MediaPlayerService", ConePlayerConstants.MEDIA_PLAYER_SERVICE_CLASS)
        assertEquals(
            listOf("ink.trantor.coneplayer", "ink.trantor.coneplayer.gp"),
            ConePlayerConstants.QUALIFIED_HOST_PACKAGES.toList()
        )
        assertEquals(2, ConePlayerConstants.KNOWN_PACKAGES.size)
        assertTrue(ConePlayerConstants.EMPTY_LYRIC_TEXTS.contains("暂无歌词"))
        assertTrue(ConePlayerConstants.LYRIC_METADATA_KEYS.contains("LYRICS"))
    }
}
