/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import java.io.File
import java.util.Locale

object SpotifyDiskCache {
    fun fileFor(cacheDir: File, languageTag: String, rawTrackId: String): File {
        val language = languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
        return File(File(cacheDir, "spotify-lyrics/$language"), "$rawTrackId.json")
    }

    fun get(cacheDir: File, languageTag: String, rawTrackId: String): String? {
        val file = fileFor(cacheDir, languageTag, rawTrackId)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun put(cacheDir: File, languageTag: String, rawTrackId: String, content: String) {
        val file = fileFor(cacheDir, languageTag, rawTrackId)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
