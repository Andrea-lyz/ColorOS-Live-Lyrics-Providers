/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.content.Context
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class QishuiCacheResolver(private val context: Context) {
    fun resolve(track: TrackIdentity): QishuiPublication? {
        val id = track.id?.trim()?.takeIf(String::isNotEmpty) ?: return null
        findNetCacheFile(id)?.let { file ->
            runCatching {
                val cache = json.decodeFromString<QishuiNetResponseCache>(file.readText())
                val lines = QishuiLyricDecoder.decode(cache)
                if (lines.isNotEmpty()) {
                    return QishuiPublication(track, lines, "net-cache")
                }
            }.onFailure { logFailure("NET_CACHE_DECODE_FAILED", it, id) }
        }
        return QishuiPlayableDbCache.find(context, id, track)
    }

    private fun findNetCacheFile(id: String): File? {
        val root = context.cacheDir.resolve("NetCacheLoader")
        if (!root.isDirectory) return null
        val targetName = md5("/luna/track_v2/" + id)
        var scanned = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && scanned < MAX_FILES) {
            val directory = stack.removeLast()
            directory.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile) {
                    scanned++
                    if (child.name == targetName) return child
                }
            }
        }
        return null
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun logFailure(event: String, throwable: Throwable, id: String) {
        StructuredDiagnostics.logWarning(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = "cache",
                event = event,
                reason = throwable.javaClass.simpleName,
                message = "mediaId=" + id
            )
        )
    }

    companion object {
        private const val MAX_FILES = 1_000
        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
