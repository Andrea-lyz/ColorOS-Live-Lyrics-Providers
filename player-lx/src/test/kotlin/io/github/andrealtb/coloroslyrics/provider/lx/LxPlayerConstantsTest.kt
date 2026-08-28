/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LxPlayerConstantsTest {

    @Test
    fun hostPackagesCoverOfficialAndWalnutOnly() {
        assertContentEquals(
            arrayOf(
                "cn.toside.music.mobile",
                "com.lxwalnut.music.mobile"
            ),
            LxPlayerConstants.QUALIFIED_HOST_PACKAGES
        )
    }

    @Test
    fun lyricModuleCandidatesPreferHostNamespaceThenFallBack() {
        assertEquals(
            listOf(
                LxPlayerConstants.LYRIC_MODULE_OFFICIAL,
                LxPlayerConstants.LYRIC_MODULE_WALNUT,
                LxPlayerConstants.LYRIC_MODULE_NETEASE
            ),
            LxPlayerConstants.lyricModuleCandidates(LxPlayerConstants.LX_OFFICIAL_PACKAGE)
        )
        assertEquals(
            listOf(
                LxPlayerConstants.LYRIC_MODULE_WALNUT,
                LxPlayerConstants.LYRIC_MODULE_NETEASE,
                LxPlayerConstants.LYRIC_MODULE_OFFICIAL
            ),
            LxPlayerConstants.lyricModuleCandidates(LxPlayerConstants.LX_WALNUT_PACKAGE)
        )
        assertTrue(LxPlayerConstants.lyricModuleCandidates("com.lxnetease.music.mobile").isEmpty())
        assertTrue(LxPlayerConstants.lyricModuleCandidates("com.ikunshare.music.mobile").isEmpty())
    }
}
