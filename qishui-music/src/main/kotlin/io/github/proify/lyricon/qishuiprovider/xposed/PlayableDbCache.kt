package io.github.proify.lyricon.qishuiprovider.xposed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.github.proify.extensions.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import io.github.proify.lyricon.qishuiprovider.xposed.parser.toRichLyric
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.LinkedHashMap

object PlayableDbCache {

    private const val TAG = "QiShuiPlayableDb"
    private const val EMPTY_LYRIC_LOG_THROTTLE_MS = 10_000L
    private val emptyLyricLogAtById = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 32
        }
    }

    data class Hit(
        val song: Song,
        val databaseName: String,
        val tableName: String
    )

    private data class Candidate(
        val file: File,
        val tableName: String
    )

    fun findSong(context: Context?, id: String, metadata: Metadata?): Hit? {
        val databasesDir = context?.getDatabasePath("placeholder")?.parentFile ?: return null
        return candidates(databasesDir)
            .firstNotNullOfOrNull { candidate ->
                readPlayableJson(candidate, id)?.let { playableJson ->
                    parseSong(playableJson, id, metadata)?.let { song ->
                        Hit(song, candidate.file.name, candidate.tableName)
                    }
                }
            }
    }

    private fun candidates(databasesDir: File): List<Candidate> {
        val files = databasesDir.listFiles().orEmpty().filter { it.isFile }
        val recent = files
            .filter { it.name.matches(Regex("""recent_played_\d+\.db""")) }
            .sortedByDescending { it.lastModified() }
            .map { Candidate(it, "cached_playable") }
        val history = files
            .filter { it.name.matches(Regex("""history_\d+\.db""")) }
            .sortedByDescending { it.lastModified() }
            .map { Candidate(it, "history_playable") }
        return recent + history
    }

    private fun readPlayableJson(candidate: Candidate, id: String): String? {
        return runCatching {
            SQLiteDatabase.openDatabase(
                candidate.file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.query(
                    candidate.tableName,
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
        }.onFailure {
            Log.w(TAG, "read failed, db=${candidate.file.name}, table=${candidate.tableName}", it)
        }.getOrNull()
    }

    private fun parseSong(playableJson: String, id: String, metadata: Metadata?): Song? {
        val playable = runCatching {
            json.decodeFromString<PlayableCache>(playableJson)
        }.onFailure {
            Log.w(TAG, "parse playable failed, id=$id", it)
        }.getOrNull()
        val track = playable?.track ?: return null
        val lyric = track.track_lyric
        val lyrics = lyric?.let { NetResponseCache(it).toRichLyric() }.orEmpty()
        if (lyrics.isEmpty()) {
            logEmptyLyricCandidateOnce(id, track.name.orEmpty())
            return null
        }
        return Song(
            id = track.id?.takeIf { it.isNotBlank() } ?: id,
            name = track.name?.takeIf { it.isNotBlank() } ?: metadata?.title.orEmpty(),
            artist = track.artistText().ifBlank { metadata?.artist.orEmpty() },
            duration = if (track.duration > 0L) track.duration else metadata?.duration ?: 0L,
            lyrics = lyrics
        )
    }

    private fun logEmptyLyricCandidateOnce(id: String, title: String) {
        val now = System.currentTimeMillis()
        synchronized(emptyLyricLogAtById) {
            val lastLoggedAt = emptyLyricLogAtById[id]
            if (lastLoggedAt != null && now - lastLoggedAt < EMPTY_LYRIC_LOG_THROTTLE_MS) {
                return
            }
            emptyLyricLogAtById[id] = now
        }
        Log.d(TAG, "skip empty lyric candidate, id=$id, title=$title")
    }

    @Serializable
    private class PlayableCache(
        val track: Track? = null
    )

    @Serializable
    private class Track(
        val id: String? = null,
        val name: String? = null,
        val duration: Long = 0L,
        val artists: List<Artist>? = null,
        val track_lyric: NetResponseCache.Lyric? = null
    ) {
        fun artistText(): String {
            return artists.orEmpty()
                .mapNotNull { artist ->
                    artist.name?.takeIf { it.isNotBlank() }
                        ?: artist.simple_display_name?.takeIf { it.isNotBlank() }
                }
                .distinct()
                .joinToString("/")
        }
    }

    @Serializable
    private class Artist(
        val name: String? = null,
        val simple_display_name: String? = null
    )
}
