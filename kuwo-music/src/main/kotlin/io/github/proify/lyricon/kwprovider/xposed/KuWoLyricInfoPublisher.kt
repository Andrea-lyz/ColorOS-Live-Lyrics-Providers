/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kwprovider.xposed

import android.media.MediaMetadata
import android.util.Log
import io.github.proify.extensions.bridge.TrackKeyBuilder
import io.github.proify.lyricon.kwprovider.BuildConfig
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale

/**
 * Publishes the current KuWo lyric into MediaSession metadata under the official
 * "lyricInfo" key. ColorOS SystemUI only populates LyricsRecyclerView after
 * loadLyricInBg sees a timed lyricInfo on the live MediaSession.
 *
 * KuWo rewrites equivalent MediaSession metadata every few seconds. The Provider
 * overlays lyricInfo on those natural writes without issuing a second metadata
 * transaction. The overlay is deliberately pure: every other field, including
 * all artwork lanes, passes through exactly as KuWo published it. Rewriting or
 * substituting artwork here diverges from native metadata behavior and turned
 * the lockscreen cover into a solid color once SystemUI recomputed artwork for
 * the lyricInfo-carrying update.
 */
object KuWoLyricInfoPublisher {
    private const val TAG = "KuWoProvider"
    private const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    private val DIAGNOSTICS_ENABLED = BuildConfig.DEBUG
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val lock = Any()
    private val pendingHostWrite = ThreadLocal<PreparedHostWrite>()

    private var latestSong: Song? = null
    private var latestGeneration = 0L

    fun prepareHostMetadata(metadata: MediaMetadata): HostMetadataDecision {
        val prepared = synchronized(lock) {
            val song = latestSong
            when {
                song == null -> PreparedHostWrite(metadata)
                !matchesCurrentTrack(metadata, song) -> copyWithoutStaleLyricInfo(metadata)
                else -> prepareCurrentTrackWrite(metadata, song)
            }
        }
        pendingHostWrite.set(prepared)
        return HostMetadataDecision(prepared.outputMetadata)
    }

    private fun prepareCurrentTrackWrite(
        metadata: MediaMetadata,
        song: Song
    ): PreparedHostWrite {
        val lyricInfo = KuWoOfficialLyricInfoEncoder
            .encode(song, latestGeneration)
            ?.value
        return if (lyricInfo == null) {
            PreparedHostWrite(metadata)
        } else {
            PreparedHostWrite(
                outputMetadata = copyWithLyricInfo(metadata, lyricInfo)
            )
        }
    }

    private fun copyWithoutStaleLyricInfo(metadata: MediaMetadata): PreparedHostWrite {
        val staleLength = metadata.getString(METADATA_KEY_LYRIC_INFO)?.length ?: 0
        val outputMetadata = MediaMetadata.Builder(metadata)
            .putString(METADATA_KEY_LYRIC_INFO, "")
            .build()
        diagnose(
            "KW_DIAG publisher cleared stale lyricInfo chars=$staleLength" +
                " artworkBitmap=${outputMetadata.hasKuWoArtworkBitmap()}" +
                " artwork=${outputMetadata.hasKuWoArtwork()}"
        )
        return PreparedHostWrite(outputMetadata)
    }

    fun onHostMetadataApplied(): Boolean {
        val prepared = pendingHostWrite.get()
        pendingHostWrite.remove()
        return prepared != null
    }

    fun onTrackChanged(generation: Long) {
        synchronized(lock) {
            latestSong = null
            latestGeneration = generation
        }
        diagnose("KW_DIAG publisher trackChanged gen=" + generation)
    }

    fun onLyricReady(song: Song, generation: Long) {
        synchronized(lock) {
            latestSong = song
            latestGeneration = generation
        }
        diagnose(
            "KW_DIAG publisher lyricReady gen=" + generation +
                " lines=" + (song.lyrics?.size ?: 0) +
                " waitingForHostMetadata=true"
        )
    }

    internal fun tracksMatch(
        metadataTitle: String?,
        metadataArtist: String?,
        metadataMediaId: String?,
        songName: String?,
        songArtist: String?,
        songId: String?
    ): Boolean {
        val mediaId = metadataMediaId.orEmpty()
        val resolvedSongId = songId.orEmpty()
        if (mediaId.isNotBlank() && resolvedSongId.isNotBlank()) {
            return mediaId == resolvedSongId
        }
        val metadataKey = TrackKeyBuilder.build(metadataTitle, metadataArtist)
        val songKey = TrackKeyBuilder.build(songName, songArtist)
        if (metadataKey.isBlank() || songKey.isBlank()) return false
        return normalizeTrackComponent(metadataKey) == normalizeTrackComponent(songKey)
    }

    private fun copyWithLyricInfo(source: MediaMetadata, lyricInfo: String): MediaMetadata {
        val result = MediaMetadata.Builder(source)
            .putString(METADATA_KEY_LYRIC_INFO, lyricInfo)
            .build()
        diagnose(
            "KW_DIAG publisher copied lyricInfo chars=${lyricInfo.length}" +
                " artworkBitmap=${result.hasKuWoArtworkBitmap()}" +
                " artwork=${result.hasKuWoArtwork()}"
        )
        return result
    }

    private fun matchesCurrentTrack(metadata: MediaMetadata, song: Song): Boolean {
        return tracksMatch(
            metadataTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadataArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadataMediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            songName = song.name,
            songArtist = song.artist,
            songId = song.id
        )
    }

    private fun normalizeTrackComponent(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
    }

    private fun diagnose(message: String) {
        if (DIAGNOSTICS_ENABLED || Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, message)
        }
    }

    data class HostMetadataDecision(
        val metadata: MediaMetadata
    )

    private data class PreparedHostWrite(
        val outputMetadata: MediaMetadata
    )
}
