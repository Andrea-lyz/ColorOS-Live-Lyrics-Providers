/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads QiShui's already-fetched TrackLyric model. No Provider network request is made.
 * Candidate names include the runtime names observed in 20.7.0 and the Kotlin metadata names.
 */
object QishuiInternalLyricsDecoder {
    private data class AccessorKey(val owner: Class<*>, val name: String)

    private val methods = ConcurrentHashMap<AccessorKey, Method>()
    private val missingMethods = ConcurrentHashMap.newKeySet<AccessorKey>()
    private val fields = ConcurrentHashMap<AccessorKey, Field>()
    private val missingFields = ConcurrentHashMap.newKeySet<AccessorKey>()

    fun resolvePlayable(remoteControlContext: Any?): Any? {
        if (remoteControlContext == null) return null
        return firstNonNull(
            callNoArg(remoteControlContext, "getA"),
            callNoArg(remoteControlContext, "d"),
            callNoArg(remoteControlContext, "getPlayable"),
            callNoArg(remoteControlContext, "getCurrentPlayable"),
            readField(remoteControlContext, "a"),
            readField(remoteControlContext, "playable")
        )
    }

    fun playableId(playable: Any?): String? {
        if (playable == null) return null
        return firstNonBlank(
            callNoArg(playable, "getPlayableId")?.toString(),
            callNoArg(playable, "getId")?.toString(),
            callNoArg(playable, "getMediaId")?.toString(),
            callNoArg(playable, "getTrackId")?.toString(),
            readField(playable, "playableId")?.toString(),
            readField(playable, "id")?.toString(),
            readField(playable, "trackId")?.toString()
        )
    }

    fun decode(playable: Any?, metadataTrack: TrackIdentity): QishuiPublication? {
        if (playable == null) return null
        val id = playableId(playable) ?: return null
        if (metadataTrack.id?.trim() != id.trim()) return null
        val lyricObject = resolveTrackLyric(playable) ?: return null
        val lyric = decodeLyric(lyricObject) ?: return null
        val lines = QishuiLyricDecoder.decode(QishuiNetResponseCache(lyric))
        if (lines.isEmpty()) return null
        val internalTrack = TrackIdentity(
            id = id,
            title = firstNonBlank(
                callNoArg(playable, "getName")?.toString(),
                callNoArg(playable, "getTitle")?.toString(),
                readField(playable, "name")?.toString(),
                readField(playable, "title")?.toString()
            ),
            artist = playableArtist(playable),
            album = firstNonBlank(
                callNoArg(playable, "getAlbumName")?.toString(),
                readField(playable, "albumName")?.toString()
            ),
            durationMs = playableDuration(playable)
        )
        return QishuiPublication(
            track = QishuiTrackMetadata.mergeMetadataFirst(metadataTrack, internalTrack),
            lines = lines,
            sourceName = "core-remote-control"
        )
    }

    private fun resolveTrackLyric(playable: Any): Any? {
        firstNonNull(
            callNoArg(playable, "getLyric"),
            callNoArg(playable, "getTrackLyric"),
            readField(playable, "lyric"),
            readField(playable, "trackLyric"),
            readField(playable, "track_lyric")
        )?.let { return it }
        val track = firstNonNull(
            callNoArg(playable, "getTrack"),
            readField(playable, "track")
        ) ?: return null
        return firstNonNull(
            callNoArg(track, "getTrackLyric"),
            callNoArg(track, "getLyric"),
            readField(track, "trackLyric"),
            readField(track, "track_lyric"),
            readField(track, "lyric")
        )
    }

    private fun decodeLyric(value: Any): QishuiNetResponseCache.Lyric? {
        val type = firstNonBlank(
            callNoArg(value, "getType")?.toString(),
            readField(value, "type")?.toString()
        )
        val content = firstNonBlank(
            callNoArg(value, "getContent")?.toString(),
            callNoArg(value, "getLyric")?.toString(),
            readField(value, "content")?.toString(),
            readField(value, "lyric")?.toString()
        )
        if (type == null || content == null) return null
        return QishuiNetResponseCache.Lyric(
            type = type,
            content = content,
            lang_translations = decodeTranslations(value)
        )
    }

    private fun decodeTranslations(value: Any): Map<String, QishuiNetResponseCache.Translation>? {
        val raw = firstNonNull(
            callNoArg(value, "getLangTranslations"),
            callNoArg(value, "getLang_translations"),
            readField(value, "langTranslations"),
            readField(value, "lang_translations")
        ) as? Map<*, *> ?: return null
        return raw.mapNotNull { (key, translation) ->
            val language = key?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val item = translation ?: return@mapNotNull null
            val content = firstNonBlank(
                callNoArg(item, "getContent")?.toString(),
                callNoArg(item, "getLyric")?.toString(),
                readField(item, "content")?.toString(),
                readField(item, "lyric")?.toString()
            ) ?: return@mapNotNull null
            language to QishuiNetResponseCache.Translation(
                content = content,
                type = firstNonBlank(
                    callNoArg(item, "getType")?.toString(),
                    readField(item, "type")?.toString()
                )
            )
        }.toMap().takeIf(Map<*, *>::isNotEmpty)
    }

    private fun playableArtist(playable: Any): String? {
        firstNonBlank(
            callNoArg(playable, "getArtistName")?.toString(),
            callNoArg(playable, "getArtistText")?.toString(),
            readField(playable, "artistName")?.toString(),
            readField(playable, "artistText")?.toString()
        )?.let { return it }
        val artists = firstNonNull(
            callNoArg(playable, "getArtists"),
            readField(playable, "artists")
        ) as? Collection<*> ?: return null
        return artists.mapNotNull { artist ->
            firstNonBlank(
                callNoArg(artist, "getName")?.toString(),
                callNoArg(artist, "getSimpleDisplayName")?.toString(),
                readField(artist, "name")?.toString(),
                readField(artist, "simple_display_name")?.toString()
            )
        }.distinct().joinToString("/").takeIf(String::isNotEmpty)
    }

    private fun playableDuration(playable: Any): Long {
        val value = firstNonNull(
            callNoArg(playable, "getDuration"),
            callNoArg(playable, "getDurationMs"),
            readField(playable, "duration"),
            readField(playable, "durationMs")
        ) as? Number ?: return 0L
        return value.toLong().takeIf { it in 1L..86_400_000L } ?: 0L
    }

    private fun callNoArg(instance: Any?, name: String): Any? {
        if (instance == null) return null
        return runCatching { findNoArgMethod(instance.javaClass, name)?.invoke(instance) }.getOrNull()
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        return runCatching { findField(instance.javaClass, name)?.get(instance) }.getOrNull()
    }

    private fun findNoArgMethod(owner: Class<*>, name: String): Method? {
        val key = AccessorKey(owner, name)
        methods[key]?.let { return it }
        if (key in missingMethods) return null
        val method = owner.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        } ?: generateSequence(owner as Class<*>?) { it.superclass }
            .firstNotNullOfOrNull { type ->
                type.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                }
            }
        if (method == null) {
            missingMethods.add(key)
            return null
        }
        method.isAccessible = true
        methods[key] = method
        return method
    }

    private fun findField(owner: Class<*>, name: String): Field? {
        val key = AccessorKey(owner, name)
        fields[key]?.let { return it }
        if (key in missingFields) return null
        val field = generateSequence(owner as Class<*>?) { it.superclass }
            .firstNotNullOfOrNull { type ->
                runCatching { type.getDeclaredField(name) }.getOrNull()
            }
        if (field == null) {
            missingFields.add(key)
            return null
        }
        field.isAccessible = true
        fields[key] = field
        return field
    }

    private fun firstNonNull(vararg values: Any?): Any? = values.firstOrNull { it != null }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
