/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.EnhanceLrcParser
import java.lang.reflect.Field
import java.lang.reflect.Method

object SaltLyricDecoder {
    private val TIMED_LRC_REGEX =
        Regex("""[\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\]>]""")

    fun decodeSong(
        song: Any,
        songClass: Class<*>,
        albumMethod: Method?,
        durationMethod: Method?
    ): SaltPublication? = decodeSong(
        song,
        SaltSongAccessors(
            id = songClass.getMethod("getId"),
            title = songClass.getMethod("getTitle"),
            artist = songClass.getMethod("getArtist"),
            album = albumMethod,
            duration = durationMethod
        )
    )

    fun decodeSong(
        song: Any,
        accessors: SaltSongAccessors
    ): SaltPublication? {
        val id = runCatching { accessors.id.invoke(song) as? String }.getOrNull() ?: return null
        val title = runCatching { accessors.title.invoke(song) as? String }.getOrNull()
        val artist = runCatching { accessors.artist.invoke(song) as? String }.getOrNull()
        if (title.isNullOrBlank() && artist.isNullOrBlank()) return null
        val album = runCatching { accessors.album?.invoke(song) as? String }.getOrNull().orEmpty()
        val durationMs = (runCatching { accessors.duration?.invoke(song) as? Number }.getOrNull())?.toLong() ?: 0L
        return SaltPublication(
            songId = id,
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            album = album,
            durationMs = durationMs.coerceAtLeast(0L),
            sourceName = "",
            timedLyric = "",
            rawCandidate = "",
            lines = emptyList()
        )
    }

    fun decodeResult(lyricResult: Any, sourceEnumClass: Class<*>): SaltLyricResult {
        val sourceField = findFirstAssignableField(lyricResult, sourceEnumClass)
        val source = sourceField?.get(lyricResult)?.toString() ?: "UNKNOWN"
        val strings = collectStrings(lyricResult)
        val timed = strings.firstOrNull { TIMED_LRC_REGEX.containsMatchIn(it) }.orEmpty()
        val raw = strings.maxByOrNull { it.length }.orEmpty()
        val enhanced = if (timed.isNotBlank()) timed else raw
        val lines = if (TIMED_LRC_REGEX.containsMatchIn(enhanced)) {
            EnhanceLrcParser.parse(enhanced).lines
        } else {
            emptyList()
        }
        return SaltLyricResult(
            sourceName = source,
            timedLyric = timed,
            rawCandidate = raw,
            lines = lines
        )
    }

    fun merge(song: SaltPublication, result: SaltLyricResult): SaltPublication =
        song.copy(
            sourceName = result.sourceName,
            timedLyric = result.timedLyric,
            rawCandidate = result.rawCandidate,
            lines = result.lines
        )

    fun findFieldValueOfType(instance: Any?, type: Class<*>): Any? {
        if (instance == null) return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (type.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    runCatching { field.get(instance) }?.getOrNull()?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    fun findFieldValueOfType(instance: Any?, typeName: String): Any? {
        if (instance == null) return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == typeName) {
                    field.isAccessible = true
                    runCatching { field.get(instance) }?.getOrNull()?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findFirstAssignableField(instance: Any, type: Class<*>): Field? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun collectStrings(instance: Any): List<String> {
        val values = mutableListOf<String>()
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    val value = runCatching { field.get(instance) }.getOrNull()
                    if (value is String) values += value
                }
            }
            current = current.superclass
        }
        return values
    }

}

data class SaltSongAccessors(
    val id: Method,
    val title: Method,
    val artist: Method,
    val album: Method?,
    val duration: Method?
)
