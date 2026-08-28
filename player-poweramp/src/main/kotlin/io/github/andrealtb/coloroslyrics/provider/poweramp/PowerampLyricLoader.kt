/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.content.Context
import android.net.Uri
import com.kyant.taglib.TagLib
import java.io.File

fun interface PowerampTextReader {
    fun read(uri: Uri): String?
}

object PowerampLyricLoader {
    fun load(
        context: Context,
        snapshot: PowerampTrackSnapshot,
        sidecarReader: PowerampTextReader = PowerampTextReader { uri ->
            readUtf8(context, uri)
        },
        embeddedReader: PowerampTextReader = PowerampTextReader { uri ->
            readEmbeddedLyric(context, uri)
        }
    ): PowerampPublication? {
        val path = snapshot.path ?: return null
        readSidecar(context, path, sidecarReader)?.let { sidecar ->
            PowerampLyricDecoder.decode(
                lyric = sidecar,
                capturedTrack = snapshot.track,
                sourceName = "sidecar-lrc",
                durationMs = snapshot.track.durationMs
            )?.let { return it }
        }
        val audioUri = resolveAudioUri(context, path) ?: return null
        val embedded = embeddedReader.read(audioUri) ?: return null
        return PowerampLyricDecoder.decode(
            lyric = embedded,
            capturedTrack = snapshot.track,
            sourceName = "embedded-tag",
            durationMs = snapshot.track.durationMs
        )
    }

    internal fun resolveAudioUri(context: Context, path: String): Uri? {
        if (PowerampPathPolicy.isAbsoluteFilesystemPath(path)) {
            val file = File(path)
            return if (file.isFile) Uri.fromFile(file) else null
        }
        val documentId = PowerampPathPolicy.formatSafDocumentId(path) ?: return null
        return PowerampSafUriResolver.resolveToUri(context, documentId)
    }

    private fun readSidecar(
        context: Context,
        path: String,
        sidecarReader: PowerampTextReader
    ): String? {
        if (PowerampPathPolicy.isAbsoluteFilesystemPath(path)) {
            val sidecar = PowerampPathPolicy.sidecarLrcPath(path) ?: return null
            val file = File(sidecar)
            if (!file.isFile) return null
            return sidecarReader.read(Uri.fromFile(file))
        }
        val documentId = PowerampPathPolicy.sidecarSafDocumentId(path) ?: return null
        val uri = PowerampSafUriResolver.resolveToUri(context, documentId) ?: return null
        return sidecarReader.read(uri)
    }

    private fun readUtf8(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun readEmbeddedLyric(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val detached = pfd.dup().detachFd()
            TagLib.getMetadata(detached)?.propertyMap?.entries?.firstOrNull { (key, _) ->
                PowerampPathPolicy.isLyricPropertyKey(key)
            }?.value?.firstOrNull()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
