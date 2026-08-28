/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpotifyDiskCacheTest {
    @Test
    fun storesJsonUnderLanguageAndTrackId() {
        val root = Files.createTempDirectory("spotify-lyrics-cache").toFile()
        try {
            val file = SpotifyDiskCache.fileFor(root, "zh-CN", "4cOdK2wGLETKBW3PvgPWqT")
            assertEquals(
                File(File(root, "spotify-lyrics/zh-CN"), "4cOdK2wGLETKBW3PvgPWqT.json"),
                file
            )
            SpotifyDiskCache.put(root, "zh-CN", "4cOdK2wGLETKBW3PvgPWqT", "{\"ok\":true}")
            assertEquals("{\"ok\":true}", SpotifyDiskCache.get(root, "zh-CN", "4cOdK2wGLETKBW3PvgPWqT"))
            assertTrue(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
