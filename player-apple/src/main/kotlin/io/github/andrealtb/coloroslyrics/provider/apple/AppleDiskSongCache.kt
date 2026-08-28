/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class AppleDiskSongCache(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val baseDir = File(
        File(context.filesDir, "apple-songs"),
        Locale.getDefault().toLanguageTag()
    )

    fun save(song: AppleSongModel): Boolean {
        if (song.adamId.isBlank() || song.lyrics.isEmpty()) return false
        return runCatching {
            val file = fileFor(song.adamId)
            file.parentFile?.mkdirs()
            file.writeBytes(gzip(json.encodeToString(AppleSongModel.serializer(), song)))
        }.isSuccess
    }

    fun load(adamId: String): AppleSongModel? {
        if (adamId.isBlank()) return null
        return runCatching {
            val file = fileFor(adamId)
            if (!file.exists()) return@runCatching null
            json.decodeFromString(AppleSongModel.serializer(), gunzip(file.readBytes()))
        }.getOrNull()
    }

    private fun fileFor(adamId: String): File = File(baseDir, "$adamId.json.gz")

    companion object {
        fun gzip(value: String): ByteArray {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(value.toByteArray(Charsets.UTF_8)) }
            return output.toByteArray()
        }

        fun gunzip(bytes: ByteArray): String =
            GZIPInputStream(ByteArrayInputStream(bytes)).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
    }
}
