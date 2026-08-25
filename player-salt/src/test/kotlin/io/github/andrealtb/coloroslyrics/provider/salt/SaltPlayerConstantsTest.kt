/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class SaltPlayerConstantsTest {
    @Test
    fun searchesLegacyAndMedia3ObfuscationPackages() {
        assertContentEquals(
            arrayOf("androidx.obf", "androidx.media3"),
            SaltPlayerConstants.lyricModelPackages()
        )
    }

    @Test
    fun returnsDefensivePackageArray() {
        val packages = SaltPlayerConstants.lyricModelPackages()
        packages[0] = "changed"
        assertEquals(
            "androidx.obf",
            SaltPlayerConstants.lyricModelPackages()[0]
        )
    }

    @Test
    fun exposesSaltActions() {
        assertEquals("com.salt.music.play_or_pause", SaltPlayerConstants.ACTION_PLAY_OR_PAUSE)
        assertEquals("com.salt.music.desktop_lyrics", SaltPlayerConstants.ACTION_DESKTOP_LYRICS)
    }
}
