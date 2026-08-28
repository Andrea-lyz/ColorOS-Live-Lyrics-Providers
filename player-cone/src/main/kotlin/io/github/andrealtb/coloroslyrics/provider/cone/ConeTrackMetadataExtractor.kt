/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

object ConeTrackMetadataExtractor {

    private val METADATA_VALUE_FIELDS = arrayOf("value", "values", "text")

    fun findSelectedAudioLyric(tracks: Any?): String? {
        if (tracks == null) return null
        return runCatching {
            val groups = invoke(tracks, "getGroups") as? Iterable<*> ?: return null
            for (group in groups) {
                if (group == null) continue
                val type = asInt(invoke(group, "getType"))
                if (type != 1) continue // C.TRACK_TYPE_AUDIO = 1
                val isSelected = asBoolean(invoke(group, "isSelected"))
                if (!isSelected) continue

                val trackCount = readIntField(group, "length")
                for (index in 0 until trackCount) {
                    val isTrackSelected = asBoolean(invoke(group, "isTrackSelected", Int::class.javaPrimitiveType, index))
                    if (!isTrackSelected) continue

                    val format = invoke(group, "getTrackFormat", Int::class.javaPrimitiveType, index)
                    val lyric = findTimedLyricInFormat(format)
                    if (ConeLyricFilter.isUsableTimedLyric(lyric)) {
                        return lyric
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun findTimedLyricInFormat(format: Any?): String? {
        if (format == null) return null
        val metadata = readField(format, "metadata") ?: return null
        val entryCount = asInt(invoke(metadata, "length"))
        for (index in 0 until entryCount) {
            val entry = invoke(metadata, "get", Int::class.javaPrimitiveType, index) ?: continue
            val lyric = extractTimedLyricFromMetadataEntry(entry)
            if (ConeLyricFilter.isUsableTimedLyric(lyric)) {
                return lyric
            }
        }
        return null
    }

    fun extractTimedLyricFromMetadataEntry(entry: Any?): String? {
        if (entry == null) return null
        val key = firstNonEmpty(
            readStringField(entry, "key"),
            readStringField(entry, "id")
        )
        val normalizedKey = key.trim().uppercase(Locale.ROOT)
        if (normalizedKey.isNotEmpty() && !ConePlayerConstants.LYRIC_METADATA_KEYS.contains(normalizedKey)) {
            return null
        }
        return findTimedStringValue(entry)
    }

    private fun findTimedStringValue(target: Any?): String? {
        if (target == null) return null
        for (fieldName in METADATA_VALUE_FIELDS) {
            val value = readField(target, fieldName)
            val lyric = findTimedStringValueRecursive(value, 0)
            if (ConeLyricFilter.isUsableTimedLyric(lyric)) {
                return lyric
            }
        }
        return null
    }

    private fun findTimedStringValueRecursive(value: Any?, depth: Int): String? {
        if (value == null || depth > 2) return null
        if (value is String) {
            return if (ConeLyricFilter.isUsableTimedLyric(value)) value else null
        }
        if (value is Iterable<*>) {
            for (item in value) {
                val lyric = findTimedStringValueRecursive(item, depth + 1)
                if (ConeLyricFilter.isUsableTimedLyric(lyric)) {
                    return lyric
                }
            }
        }
        return null
    }

    private fun invoke(target: Any?, methodName: String, paramType: Class<*>? = null, arg: Any? = null): Any? {
        if (target == null) return null
        val method: Method = if (paramType == null) {
            target.javaClass.getMethod(methodName)
        } else {
            target.javaClass.getMethod(methodName, paramType)
        }
        method.isAccessible = true
        return if (paramType == null) method.invoke(target) else method.invoke(target, arg)
    }

    private fun readField(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        var current: Class<*>? = target.javaClass
        while (current != null) {
            try {
                val field: Field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun readStringField(target: Any?, fieldName: String): String {
        return (readField(target, fieldName) as? String).orEmpty()
    }

    private fun readIntField(target: Any?, fieldName: String): Int {
        return asInt(readField(target, fieldName))
    }

    private fun asInt(value: Any?): Int = (value as? Number)?.toInt() ?: 0
    private fun asBoolean(value: Any?): Boolean = (value as? Boolean) == true

    private fun firstNonEmpty(first: String?, second: String?): String {
        return if (!first.isNullOrEmpty()) first else second.orEmpty()
    }
}
