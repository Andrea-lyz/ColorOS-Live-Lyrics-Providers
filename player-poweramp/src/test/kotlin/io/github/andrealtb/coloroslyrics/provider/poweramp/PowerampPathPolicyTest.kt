/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerampPathPolicyTest {
    @Test
    fun formatsPowerampSafPathToDocumentId() {
        assertEquals(
            "primary:Music/Jay/song.flac",
            PowerampPathPolicy.formatSafDocumentId("primary/Music/Jay/song.flac")
        )
        assertNull(PowerampPathPolicy.formatSafDocumentId("/storage/emulated/0/Music/song.mp3"))
        assertNull(PowerampPathPolicy.formatSafDocumentId("primary"))
        assertTrue(PowerampPathPolicy.isAbsoluteFilesystemPath("/sdcard/a.mp3"))
    }

    @Test
    fun sidecarLrcKeepsDirectoryAndVolume() {
        assertEquals(
            "/storage/emulated/0/Music/song.lrc",
            PowerampPathPolicy.sidecarLrcPath("/storage/emulated/0/Music/song.mp3")
        )
        assertEquals(
            "primary:Music/Jay/song.lrc",
            PowerampPathPolicy.sidecarSafDocumentId("primary/Music/Jay/song.flac")
        )
        assertNull(PowerampPathPolicy.sidecarLrcPath("song-without-extension"))
    }

    @Test
    fun lyricPropertyKeysDoNotMatchLyricist() {
        assertTrue(PowerampPathPolicy.isLyricPropertyKey("LYRICS"))
        assertTrue(PowerampPathPolicy.isLyricPropertyKey("unsyncedlyrics"))
        assertTrue(PowerampPathPolicy.isLyricPropertyKey("USLT"))
        assertFalse(PowerampPathPolicy.isLyricPropertyKey("LYRICIST"))
        assertFalse(PowerampPathPolicy.isLyricPropertyKey("TITLE"))
    }
}
