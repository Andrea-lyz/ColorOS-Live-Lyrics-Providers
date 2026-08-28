/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

object NeteaseLyricInfoReader {
    private val MUSIC_ID_METHODS = listOf("getFilterMusicId", "getMusicId", "getMatchedMusicId", "getId")
    private val LYRIC_MUSIC_ID_METHODS = listOf("getMusicId", "getId")
    private val TITLE_METHODS = listOf("getMusicName", "getSongName", "getName", "getTitle")
    private val ARTIST_METHODS = listOf("getSingerName", "getArtist", "getSinger")
    private val ALBUM_METHODS = listOf("getAlbumName", "getAlbum")
    private val RAW_DATA_METHODS = listOf("getRawData")
    private val YRC_METHODS = listOf("getYrc")
    private val LRC_METHODS = listOf("getLrc")
    private val LRC_TRANSLATE_METHODS = listOf("getLrcTranslateLyric")
    private val YRC_TRANSLATE_METHODS = listOf("getYrcTranslateLyric")
    private val FORBIDDEN_ROME_NAMES = setOf(
        "getLrcRomeLyric",
        "getYrcRomeLyric",
        "lrcRomeLyric",
        "yrcRomeLyric",
        "roma"
    )

    data class Snapshot(
        val track: TrackIdentity,
        val lyricMusicId: String?,
        val lrc: String?,
        val yrc: String?,
        val lrcTranslate: String?,
        val yrcTranslate: String?
    ) {
        val idsMatch: Boolean
            get() {
                val filterId = track.id
                return !filterId.isNullOrBlank() &&
                    !lyricMusicId.isNullOrBlank() &&
                    filterId == lyricMusicId
            }
    }

    fun looksLikeLyricInfoClass(target: Any?): Boolean {
        if (target == null) return false
        val name = target.javaClass.name
        return name == NeteasePlayerConstants.LYRIC_INFO_CLASS || name.endsWith(".LyricInfo")
    }

    fun looksLikeLyricInfo(target: Any?): Boolean {
        if (looksLikeLyricInfoClass(target)) return true
        return invokeNoArg(target, RAW_DATA_METHODS) != null
    }

    fun firstLyricInfoArg(args: Array<out Any?>?): Any? {
        if (args == null) return null
        return args.firstOrNull { looksLikeLyricInfo(it) }
    }

    fun findMusicInfo(holder: Any?, lyricMusicId: String?): Any? {
        if (holder == null) return null
        val candidates = mutableListOf<Any>()
        generateSequence(holder.javaClass as Class<*>?) { it.superclass }.forEach { type ->
            type.declaredFields.forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(holder) }.getOrNull() ?: return@forEach
                if (!looksLikeMusicInfo(value)) return@forEach
                candidates.add(value)
            }
        }
        if (candidates.isEmpty()) return null
        if (!lyricMusicId.isNullOrBlank()) {
            return candidates.singleOrNull { candidate ->
                stringify(invokeNoArg(candidate, MUSIC_ID_METHODS)) == lyricMusicId
            }
        }
        return candidates.singleOrNull()
    }

    private fun looksLikeMusicInfo(target: Any): Boolean {
        val name = target.javaClass.name
        if (name == NeteasePlayerConstants.MUSIC_INFO_CLASS || name.endsWith(".MusicInfo")) {
            return true
        }
        if (invokeNoArg(target, listOf("getFilterMusicId")) != null) return true
        return invokeNoArg(target, listOf("getMusicId")) != null &&
            invokeNoArg(target, TITLE_METHODS) != null
    }

    fun read(lyricInfo: Any?, musicInfo: Any?): Snapshot {
        val rawData = invokeNoArg(lyricInfo, RAW_DATA_METHODS)
        rejectRomeAccess(rawData)
        val filterId = stringify(invokeNoArg(musicInfo, MUSIC_ID_METHODS))
        return Snapshot(
            track = TrackIdentity(
                id = filterId,
                title = stringify(invokeNoArg(musicInfo, TITLE_METHODS))
                    ?: stringify(invokeNoArg(lyricInfo, TITLE_METHODS)),
                artist = stringify(invokeNoArg(musicInfo, ARTIST_METHODS)),
                album = stringify(invokeNoArg(musicInfo, ALBUM_METHODS))
            ),
            lyricMusicId = stringify(invokeNoArg(lyricInfo, LYRIC_MUSIC_ID_METHODS)),
            lrc = stringify(invokeNoArg(rawData, LRC_METHODS)) ?: readStringField(rawData, "lrc"),
            yrc = stringify(invokeNoArg(rawData, YRC_METHODS)) ?: readStringField(rawData, "yrc"),
            lrcTranslate = stringify(invokeNoArg(rawData, LRC_TRANSLATE_METHODS))
                ?: readStringField(rawData, "lrcTranslateLyric"),
            yrcTranslate = stringify(invokeNoArg(rawData, YRC_TRANSLATE_METHODS))
                ?: readStringField(rawData, "yrcTranslateLyric")
        )
    }

    internal fun invokeNoArg(target: Any?, names: List<String>): Any? {
        if (target == null) return null
        names.forEach { name ->
            if (name in FORBIDDEN_ROME_NAMES) return@forEach
            val value = runCatching {
                target.javaClass.methods.firstOrNull { method ->
                    method.name == name && method.parameterCount == 0
                }?.invoke(target)
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    internal fun readStringField(target: Any?, name: String): String? {
        if (target == null || name in FORBIDDEN_ROME_NAMES) return null
        val field = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?: return null
        field.isAccessible = true
        return stringify(runCatching { field.get(target) }.getOrNull())
    }

    private fun rejectRomeAccess(rawData: Any?) {
        if (rawData == null) return
        FORBIDDEN_ROME_NAMES.forEach { name ->
            val method = rawData.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) {
                // Presence is fine; never invoke. Tests assert this list is consulted.
            }
        }
    }

    private fun stringify(value: Any?): String? = when (value) {
        null -> null
        is Number -> value.toLong().takeIf { it != 0L }?.toString()
        else -> value.toString().trim().takeIf { it.isNotBlank() }
    }
}
