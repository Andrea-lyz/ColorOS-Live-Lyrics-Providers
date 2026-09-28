/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseMediaIdPolicyTest {

    @Test
    fun plainSongIdIsUnchanged() {
        assertEquals("1927432302", NeteaseMediaIdPolicy.normalize("1927432302"))
    }

    @Test
    fun carLinkBrowseIdResolvesToSongId() {
        // Observed on ColorOS 17 CarLink: OCar playFromMediaId("other_id__1927432302").
        assertEquals("1927432302", NeteaseMediaIdPolicy.normalize("other_id__1927432302"))
        assertEquals("1927432302", NeteaseMediaIdPolicy.normalize(" other_id__1927432302 "))
    }

    @Test
    fun carLinkPlaylistBrowseIdResolvesToSongId() {
        // Observed on ColorOS 17 CarLink projection: playlist_id_<playlistId>_<songId>.
        assertEquals(
            "26129421",
            NeteaseMediaIdPolicy.normalize("playlist_id_86192403_26129421")
        )
    }

    @Test
    fun browseIdWithoutSongYieldsItsTrailingNumber() {
        // A playlist node is never the playing item; its number cannot match a song payload.
        assertEquals("86192403", NeteaseMediaIdPolicy.normalize("playlist_id_86192403"))
    }

    @Test
    fun nonNumericBrowseTailIsKeptVerbatim() {
        assertEquals("other_id__abc", NeteaseMediaIdPolicy.normalize("other_id__abc"))
        assertEquals("other_id__", NeteaseMediaIdPolicy.normalize("other_id__"))
        assertEquals("local_file_12", NeteaseMediaIdPolicy.normalize("local_file_12"))
    }

    @Test
    fun blankMediaIdIsAbsent() {
        assertNull(NeteaseMediaIdPolicy.normalize(null))
        assertNull(NeteaseMediaIdPolicy.normalize(""))
        assertNull(NeteaseMediaIdPolicy.normalize("   "))
    }
}
