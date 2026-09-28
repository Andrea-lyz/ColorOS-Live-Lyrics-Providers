/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.HostMetadataOverlay
import java.lang.ref.WeakReference

/**
 * Overlays patched lyricInfo onto QQ's own MediaSession writes when lyrics
 * arrive after the official Compat builder path. The payload is appended into
 * QQ's metadata object itself (HostMetadataOverlay), so artwork and every other
 * host field reach SystemUI exactly as QQ published them. At most one replay per
 * generation, re-sending that same host object.
 */
object QqLyricInfoPublisher {
    private val lock = Any()
    private val pendingHostWrite = ThreadLocal<MediaMetadata>()

    @Volatile
    private var selfPublishing = false
    private var latestPublication: QqPublication? = null
    private var lastSession: WeakReference<MediaSession>? = null
    private var lastHostMetadata: MediaMetadata? = null
    private var lastPublishedFingerprint = ""
    private var replayedGeneration = 0L

    fun isSelfPublishing(): Boolean = selfPublishing

    fun onTrackChanged(generation: Long) {
        synchronized(lock) {
            latestPublication = null
            lastPublishedFingerprint = ""
            if (replayedGeneration != generation) {
                replayedGeneration = 0L
            }
        }
        QqDiagnostics.debug(
            area = "publisher",
            event = "TRACK_CHANGED",
            generation = generation
        )
    }

    fun onLyricReady(publication: QqPublication): MediaSession? {
        val session = synchronized(lock) {
            latestPublication = publication
            lastSession?.get()
        }
        QqDiagnostics.debug(
            area = "publisher",
            event = "LYRIC_READY",
            generation = publication.generation,
            message = "lines=${publication.lines.size} source=${publication.source}"
        )
        return session
    }

    fun prepareHostMetadata(
        session: MediaSession?,
        metadata: MediaMetadata,
        hostPackage: String
    ) {
        synchronized(lock) {
            if (session != null) {
                lastSession = WeakReference(session)
            }
            lastHostMetadata = metadata
            val publication = latestPublication
            if (publication != null &&
                matchesCurrentTrack(metadata, publication.track, hostPackage)
            ) {
                overlay(metadata, publication, hostPackage)
            }
        }
        pendingHostWrite.set(metadata)
    }

    fun onHostMetadataApplied() {
        pendingHostWrite.remove()
    }

    fun replayIfNeeded(hostPackage: String): Boolean {
        val request = synchronized(lock) {
            if (selfPublishing) return false
            val publication = latestPublication ?: return false
            if (publication.generation == replayedGeneration) return false
            val session = lastSession?.get() ?: return false
            val metadata = lastHostMetadata ?: return false
            if (!matchesCurrentTrack(metadata, publication.track, hostPackage)) return false
            val result = overlay(metadata, publication, hostPackage) ?: return false
            if (result == HostMetadataOverlay.Result.UNCHANGED) {
                replayedGeneration = publication.generation
                return false
            }
            if (!result.isApplied) return false
            replayedGeneration = publication.generation
            ReplayRequest(session, metadata, publication.generation)
        }
        return runCatching {
            selfPublishing = true
            request.session.setMetadata(request.metadata)
            true
        }.getOrElse {
            synchronized(lock) {
                if (replayedGeneration == request.generation) {
                    replayedGeneration = 0L
                }
            }
            QqDiagnostics.error(
                area = "publisher",
                event = "REPLAY_FAILED",
                message = it.message
            )
            false
        }.also {
            selfPublishing = false
        }
    }

    /**
     * Appends the encoded payload into [metadata] itself. The official fields are read from
     * the lyricInfo QQ put on this object, never from a payload this hook wrote into it.
     * Returns null when nothing could be encoded.
     */
    internal fun overlay(
        metadata: MediaMetadata,
        publication: QqPublication,
        hostPackage: String
    ): HostMetadataOverlay.Result? {
        val existing = HostMetadataOverlay.hostLyricInfo(metadata)
        val encoded = QqOfficialLyricInfoEncoder.encode(
            track = publication.track,
            lines = publication.lines,
            trackGeneration = publication.generation,
            hostPackage = hostPackage,
            existingLyricInfo = existing
        ) ?: return null
        val fingerprint = publication.track.id.orEmpty() + ':' +
            publication.generation + ':' + encoded.value.hashCode()
        if (fingerprint == lastPublishedFingerprint &&
            metadata.getString(QqPlayerConstants.METADATA_KEY_LYRIC_INFO) == encoded.value
        ) {
            return HostMetadataOverlay.Result.UNCHANGED
        }
        val result = HostMetadataOverlay.putLyricInfo(metadata, encoded.value).result
        if (!result.isApplied) {
            QqDiagnostics.debug(
                area = "publisher",
                event = "LYRIC_INFO_APPEND_SKIPPED",
                generation = publication.generation,
                payloadChars = encoded.value.length,
                reason = result.name
            )
            return result
        }
        lastPublishedFingerprint = fingerprint
        QqDiagnostics.debug(
            area = "publisher",
            event = "LYRIC_INFO_PATCHED",
            generation = publication.generation,
            payloadChars = encoded.value.length,
            message = "source=${publication.source} raw=${encoded.rawLyric.isNotBlank()} " +
                "translation=${encoded.translationLyric.isNotBlank()}"
        )
        return result
    }

    internal fun matchesCurrentTrack(
        metadata: MediaMetadata,
        track: TrackIdentity,
        hostPackage: String
    ): Boolean {
        val lyricInfo = HostMetadataOverlay.hostLyricInfo(metadata)
        val songId = QqOfficialLyricInfoEncoder.extractJsonString(lyricInfo.orEmpty(), "songId")
        if (!track.id.isNullOrBlank() && !songId.isNullOrBlank() && track.id == songId) {
            return true
        }
        val hostTrack = TrackIdentity(
            id = songId,
            title = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ),
            artist = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
        return TrackIdentityPolicy.isSameTrack(track, hostTrack)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private data class ReplayRequest(
        val session: MediaSession,
        val metadata: MediaMetadata,
        val generation: Long
    )
}
