/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.util.LinkedHashMap

object KuGouLyricsCache {
    private const val MAX_CACHE_KEYS = 384
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CachedLyrics>(MAX_CACHE_KEYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean {
            return size > MAX_CACHE_KEYS
        }
    }

    private data class CachedLyrics(
        val lyrics: List<RichLyricLine>,
        val keys: Set<String>
    )

    fun put(keys: Iterable<String>, lyrics: List<RichLyricLine>) {
        val normalizedKeys = keys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedKeys.isEmpty() || lyrics.isEmpty()) return
        val cached = CachedLyrics(lyrics, normalizedKeys)
        synchronized(lock) {
            normalizedKeys.forEach { key ->
                cache[key] = cached
            }
        }
    }

    fun get(keys: Iterable<String>): List<RichLyricLine>? {
        synchronized(lock) {
            keys.forEach { key ->
                val normalizedKey = key.trim()
                if (normalizedKey.isBlank()) return@forEach
                cache[normalizedKey]?.let { return it.lyrics }
            }
        }
        return null
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    fun size(): Int = synchronized(lock) { cache.size }
}
