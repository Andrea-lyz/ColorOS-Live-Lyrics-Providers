/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.publisher.HostMetadataOverlay
import io.github.proify.extensions.bridge.TrackKeyBuilder
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale

/**
 * Publishes the current KuWo lyric into the host MediaSession metadata under the official
 * "lyricInfo" key. ColorOS SystemUI only populates LyricsRecyclerView after loadLyricInBg sees a
 * timed lyricInfo on the live MediaSession.
 *
 * The overlay is append-only. ColorOS derives the lockscreen cover from the bitmap lanes of exactly
 * the metadata the host published
 * (com.oplus.systemui.media.controls.pipeline.OplusMediaDataManagerExImpl tries loadBitmapFromUri,
 * then METADATA_KEY_ART, then METADATA_KEY_ALBUM_ART, and KuWo natively publishes ALBUM_ART), so
 * this Provider never rebuilds the metadata and never rewrites an artwork lane. It writes the single
 * "lyricInfo" key into the bundle the host already carries, which keeps every other field, bitmap
 * object and unknown key byte-identical to what KuWo published. Rebuilding through
 * MediaMetadata.Builder would hand SystemUI a different metadata object and is how the lockscreen
 * cover previously degraded to a solid color.
 */
object KuWoLyricInfoPublisher {
    private const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val lock = Any()
    private val pendingHostWrite = ThreadLocal<Boolean>()

    private var latestSong: Song? = null
    private var latestGeneration = 0L

    @Volatile
    private var appendUnsupportedLogged = false

    fun prepareHostMetadata(metadata: MediaMetadata): HostMetadataDecision {
        val appended = synchronized(lock) { overlayLyricInfo(metadata) }
        // Every host write that passed through the overlay counts as an applied host metadata, so the
        // caller keeps its identity/generation bookkeeping exactly as before.
        pendingHostWrite.set(true)
        return HostMetadataDecision(metadata = metadata, lyricInfoAppended = appended)
    }

    fun onHostMetadataApplied(): Boolean {
        val applied = pendingHostWrite.get()
        pendingHostWrite.remove()
        return applied != null
    }

    fun onTrackChanged(generation: Long) {
        synchronized(lock) {
            latestSong = null
            latestGeneration = generation
        }
        diagnose(event = "TRACK_CHANGED", message = "gen=$generation")
    }

    fun onLyricReady(song: Song, generation: Long) {
        synchronized(lock) {
            latestSong = song
            latestGeneration = generation
        }
        diagnose(
            event = "LYRIC_READY",
            message = "gen=$generation lines=" + (song.lyrics?.size ?: 0) + " waitingForHostMetadata=true"
        )
    }

    private fun overlayLyricInfo(metadata: MediaMetadata): Boolean {
        val song = latestSong
        val trackMatches = song != null && matchesCurrentTrack(metadata, song)
        val newValue = if (trackMatches) {
            KuWoOfficialLyricInfoEncoder.encode(song, latestGeneration)?.value
        } else {
            null
        }
        val currentValue = runCatching { metadata.getString(METADATA_KEY_LYRIC_INFO) }.getOrNull()
        return when (KuWoLyricOverlayPolicy.decide(
            songAvailable = song != null,
            trackMatches = trackMatches,
            currentValue = currentValue,
            newValue = newValue
        )) {
            KuWoLyricOverlayAction.APPEND -> appendLyricInfo(metadata, newValue.orEmpty())

            KuWoLyricOverlayAction.CLEAR -> clearStaleLyricInfo(metadata, currentValue)

            KuWoLyricOverlayAction.NOOP -> false
        }
    }

    private fun appendLyricInfo(metadata: MediaMetadata, lyricInfo: String): Boolean {
        val result = HostMetadataOverlay.putLyricInfo(metadata, lyricInfo).result
        when (result) {
            HostMetadataOverlay.Result.WRITTEN -> {
                KuWoArtworkDiagnostics.log("APPENDED", metadata)
                diagnose(event = "LYRIC_INFO_APPENDED", message = "chars=" + lyricInfo.length)
            }

            HostMetadataOverlay.Result.UNCHANGED -> Unit
            HostMetadataOverlay.Result.UNSUPPORTED -> logAppendUnsupported()
            HostMetadataOverlay.Result.FIELD_TOO_LARGE,
            HostMetadataOverlay.Result.PARCEL_TOO_LARGE,
            HostMetadataOverlay.Result.MEASUREMENT_FAILED -> diagnose(
                event = "LYRIC_INFO_OVERSIZE_SKIPPED",
                message = "reason=$result chars=" + lyricInfo.length
            )
        }
        return result == HostMetadataOverlay.Result.WRITTEN
    }

    private fun clearStaleLyricInfo(metadata: MediaMetadata, staleValue: String?): Boolean {
        return when (HostMetadataOverlay.clearLyricInfo(metadata)) {
            HostMetadataOverlay.Result.WRITTEN -> {
                KuWoArtworkDiagnostics.log("STALE_CLEARED", metadata)
                diagnose(
                    event = "LYRIC_INFO_STALE_CLEARED",
                    message = "chars=" + (staleValue?.length ?: 0)
                )
                true
            }

            HostMetadataOverlay.Result.UNSUPPORTED -> {
                logAppendUnsupported()
                false
            }

            else -> false
        }
    }

    private fun logAppendUnsupported() {
        if (appendUnsupportedLogged) return
        appendUnsupportedLogged = true
        diagnose(
            event = "LYRIC_INFO_APPEND_UNSUPPORTED",
            message = "reason=bundle-unresolved overlay=skipped"
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

    private fun diagnose(event: String, message: String) {
        KuWoDiagnostics.debug(
            area = "publisher",
            event = event,
            message = message
        )
    }

    data class HostMetadataDecision(
        val metadata: MediaMetadata,
        val lyricInfoAppended: Boolean
    )
}
