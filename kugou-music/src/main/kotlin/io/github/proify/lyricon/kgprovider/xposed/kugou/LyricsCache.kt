/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.kgprovider.xposed.kugou

import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine

object LyricsCache {

    private const val TAG = "LyricsCache"
    private const val MAX_CACHE_KEYS = 384

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CachedLyrics>(MAX_CACHE_KEYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean {
            return size > MAX_CACHE_KEYS
        }
    }

    private data class CachedLyrics(
        val lyrics: List<RichLyricLine>,
        val keys: Set<String>
    )

    fun put(songId: String, lyrics: List<RichLyricLine>) {
        put(listOf(songId), lyrics)
    }

    fun put(keys: Iterable<String>, lyrics: List<RichLyricLine>) {
        val normalizedKeys = keys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedKeys.isEmpty()) return
        val cached = CachedLyrics(lyrics, normalizedKeys)
        synchronized(cacheLock) {
            normalizedKeys.forEach { key ->
                cache[key] = cached
            }
        }
        debug("Cached lyrics for keys=${normalizedKeys.joinToString(limit = 4)} (${lyrics.size} lines)")
    }

    fun get(songId: String): List<RichLyricLine>? {
        return get(listOf(songId))
    }

    fun get(keys: Iterable<String>): List<RichLyricLine>? {
        synchronized(cacheLock) {
            keys.forEach { key ->
                val normalizedKey = key.trim()
                if (normalizedKey.isBlank()) return@forEach
                val cached = cache[normalizedKey]
                if (cached != null) {
                    debug("Cache hit for: $normalizedKey")
                    return cached.lyrics
                }
            }
        }
        debug("Cache miss")
        return null
    }

    fun has(songId: String): Boolean = synchronized(cacheLock) {
        cache.containsKey(songId.trim())
    }

    fun remove(songId: String) {
        synchronized(cacheLock) {
            cache.remove(songId.trim())
        }
        debug("Removed cache for: $songId")
    }

    fun clear() {
        synchronized(cacheLock) {
            cache.clear()
        }
        debug("Cache cleared")
    }

    fun size(): Int = synchronized(cacheLock) { cache.size }

    private fun debug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }
}
