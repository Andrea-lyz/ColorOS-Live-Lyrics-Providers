/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("PropertyName")

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import kotlinx.serialization.Serializable
import java.io.File

object QishuiPlayableDbCache {
    private data class Candidate(val file: File, val table: String)

    fun find(context: Context, id: String, metadataTrack: TrackIdentity): QishuiPublication? {
        val directory = context.getDatabasePath("placeholder").parentFile ?: return null
        return candidates(directory).firstNotNullOfOrNull { candidate ->
            readPlayableJson(candidate, id)?.let { value ->
                decode(value, id, metadataTrack, candidate)
            }
        }
    }

    private fun candidates(directory: File): List<Candidate> {
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        val recent = files
            .filter { it.name.matches(Regex("""recent_played_\d+\.db""")) }
            .sortedByDescending(File::lastModified)
            .map { Candidate(it, "cached_playable") }
        val history = files
            .filter { it.name.matches(Regex("""history_\d+\.db""")) }
            .sortedByDescending(File::lastModified)
            .map { Candidate(it, "history_playable") }
        return recent + history
    }

    private fun readPlayableJson(candidate: Candidate, id: String): String? =
        runCatching {
            SQLiteDatabase.openDatabase(
                candidate.file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { database ->
                database.query(
                    candidate.table,
                    arrayOf("playableJson"),
                    "id = ?",
                    arrayOf(id),
                    null,
                    null,
                    null,
                    "1"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        }.getOrNull()

    private fun decode(
        value: String,
        id: String,
        metadataTrack: TrackIdentity,
        candidate: Candidate
    ): QishuiPublication? {
        val playable = runCatching {
            QishuiCacheResolver.json.decodeFromString<PlayableCache>(value)
        }.getOrNull() ?: return null
        val track = playable.track ?: return null
        val lines = track.track_lyric
            ?.let { QishuiLyricDecoder.decode(QishuiNetResponseCache(it)) }
            .orEmpty()
        if (lines.isEmpty()) return null
        val internal = TrackIdentity(
            id = track.id?.takeIf(String::isNotBlank) ?: id,
            title = track.name,
            artist = track.artists.orEmpty()
                .mapNotNull { it.name?.takeIf(String::isNotBlank) ?: it.simple_display_name }
                .distinct()
                .joinToString("/")
                .takeIf(String::isNotBlank),
            durationMs = track.duration.takeIf { it > 0L } ?: 0L
        )
        return QishuiPublication(
            track = QishuiTrackMetadata.mergeMetadataFirst(metadataTrack, internal),
            lines = lines,
            sourceName = "db:" + candidate.file.name + "/" + candidate.table
        )
    }

    @Serializable
    private data class PlayableCache(val track: Track? = null)

    @Serializable
    private data class Track(
        val id: String? = null,
        val name: String? = null,
        val duration: Long = 0L,
        val artists: List<Artist>? = null,
        val track_lyric: QishuiNetResponseCache.Lyric? = null
    )

    @Serializable
    private data class Artist(
        val name: String? = null,
        val simple_display_name: String? = null
    )
}
