package io.github.proify.lyricon.qishuiprovider.xposed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog
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
    private const val DB_DIAGNOSTIC_LOGS = false
    private const val EMPTY_LYRIC_LOG_THROTTLE_MS = 10_000L
    private const val DB_DIAGNOSTIC_LOG_THROTTLE_MS = 10_000L
    private val emptyLyricLogAtById = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 32
        }
    }
    private val diagnosticLogAtById = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
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
        val primaryCandidates = candidates(databasesDir)
        findHit(primaryCandidates, id, metadata)?.let { return it }

        val discoveredCandidates = discoverPlayableCandidates(databasesDir, primaryCandidates)
        findHit(discoveredCandidates, id, metadata)?.let { hit ->
            YLog.debug(
                tag = TAG,
                msg = "db discovered hit, mediaId=$id, db=${hit.databaseName}, " +
                    "table=${hit.tableName}, lyrics=${hit.song.lyrics?.size ?: 0}"
            )
            return hit
        }

        logDatabaseDiagnostic(databasesDir, id, primaryCandidates, discoveredCandidates)
        return null
    }

    private fun findHit(
        candidates: List<Candidate>,
        id: String,
        metadata: Metadata?
    ): Hit? {
        return candidates.firstNotNullOfOrNull { candidate ->
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

    private fun discoverPlayableCandidates(
        databasesDir: File,
        primaryCandidates: List<Candidate>
    ): List<Candidate> {
        val known = primaryCandidates
            .map { it.file.absolutePath to it.tableName }
            .toMutableSet()
        return databasesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".db") }
            .sortedByDescending { it.lastModified() }
            .flatMap { file ->
                discoverPlayableTables(file).map { tableName -> Candidate(file, tableName) }
            }
            .filter { known.add(it.file.absolutePath to it.tableName) }
    }

    private fun discoverPlayableTables(file: File): List<String> {
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                tableNames(db).filter { tableName ->
                    val columns = columnNames(db, tableName)
                    columns.contains("id") && columns.contains("playableJson")
                }
            }
        }.onFailure {
            Log.w(TAG, "discover tables failed, db=${file.name}", it)
        }.getOrDefault(emptyList())
    }

    private fun tableNames(db: SQLiteDatabase): List<String> {
        return db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table'",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }

    private fun columnNames(db: SQLiteDatabase, tableName: String): Set<String> {
        return db.rawQuery(
            "PRAGMA table_info(${quoteIdentifier(tableName)})",
            null
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }

    private fun quoteIdentifier(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

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
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.also {
                            YLog.debug(
                                tag = TAG,
                                msg = "db row hit, mediaId=$id, db=${candidate.file.name}, " +
                                    "table=${candidate.tableName}, chars=${it.length}"
                            )
                        }
                    } else null
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
        if (DB_DIAGNOSTIC_LOGS) {
            Log.d(TAG, "skip empty lyric candidate, id=$id, title=$title")
        }
        YLog.debug(tag = TAG, msg = "skip empty lyric candidate, id=$id, title=$title")
    }

    private fun logDatabaseDiagnostic(
        databasesDir: File,
        id: String,
        primaryCandidates: List<Candidate>,
        discoveredCandidates: List<Candidate>
    ) {
        val now = System.currentTimeMillis()
        synchronized(diagnosticLogAtById) {
            val lastLoggedAt = diagnosticLogAtById[id]
            if (lastLoggedAt != null && now - lastLoggedAt < DB_DIAGNOSTIC_LOG_THROTTLE_MS) {
                return
            }
            diagnosticLogAtById[id] = now
        }

        val dbFiles = databasesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".db") }
            .sortedByDescending { it.lastModified() }
        val sampleDbs = dbFiles
            .take(8)
            .joinToString("|") { "${it.name}:${it.length()}" }
        val sampleDiscovered = discoveredCandidates
            .take(8)
            .joinToString("|") { "${it.file.name}.${it.tableName}" }
        YLog.debug(
            tag = TAG,
            msg = "db diag, mediaId=$id, dir=${databasesDir.absolutePath}, " +
                "dirExists=${databasesDir.exists()}, dbFiles=${dbFiles.size}, " +
                "primaryCandidates=${primaryCandidates.size}, " +
                "discoveredCandidates=${discoveredCandidates.size}, sampleDbs=$sampleDbs, " +
                "sampleDiscovered=$sampleDiscovered"
        )
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
