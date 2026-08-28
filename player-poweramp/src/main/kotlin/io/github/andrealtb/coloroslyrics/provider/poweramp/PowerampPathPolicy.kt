/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

object PowerampPathPolicy {
    fun formatSafDocumentId(path: String): String? {
        val input = path.trimStart()
        if (input.isEmpty() || input.startsWith("/")) return null
        val separatorIndex = input.indexOf('/')
        if (separatorIndex <= 0) return null
        val volumeId = input.take(separatorIndex)
        val relativePath = input.substring(separatorIndex + 1)
        if (relativePath.isEmpty()) return null
        return "$volumeId:$relativePath"
    }

    fun isAbsoluteFilesystemPath(path: String): Boolean = path.startsWith("/")

    fun sidecarLrcPath(path: String): String? = replaceExtension(path, "lrc")

    fun sidecarSafDocumentId(path: String): String? {
        val documentId = formatSafDocumentId(path) ?: return null
        return replaceExtension(documentId, "lrc")
    }

    fun isLyricPropertyKey(key: String): Boolean {
        val normalized = key.trim()
        return normalized.equals("LYRICS", ignoreCase = true) ||
            normalized.equals("UNSYNCEDLYRICS", ignoreCase = true) ||
            normalized.equals("USLT", ignoreCase = true)
    }

    internal fun replaceExtension(path: String, extension: String): String? {
        val nameStart = maxOf(path.lastIndexOf('/'), path.lastIndexOf(':')) + 1
        if (nameStart <= 0 || nameStart >= path.length) return null
        val name = path.substring(nameStart)
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        return path.substring(0, nameStart) + name.substring(0, dot + 1) + extension
    }
}
