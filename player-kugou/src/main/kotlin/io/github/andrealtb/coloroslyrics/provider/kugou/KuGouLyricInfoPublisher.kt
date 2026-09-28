/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.core.publisher.HostMetadataOverlay
import java.lang.ref.WeakReference

/**
 * Overlays patched lyricInfo onto KuGou's own MediaSession writes. The payload is
 * appended into KuGou's metadata object itself (HostMetadataOverlay), so artwork and
 * every other host field reach SystemUI exactly as KuGou published them. A second
 * write re-sends that same host object at most once per generation when lyrics
 * arrive after the host metadata.
 */
object KuGouLyricInfoPublisher {
    private val lock = Any()
    private val pendingHostWrite = ThreadLocal<MediaMetadata>()

    @Volatile
    private var selfPublishing = false
    private var latestPublication: KuGouPublication? = null
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
        KuGouDiagnostics.debug(
            area = "publisher",
            event = "TRACK_CHANGED",
            generation = generation
        )
    }

    fun onLyricReady(publication: KuGouPublication): MediaSession? {
        val session = synchronized(lock) {
            latestPublication = publication
            lastSession?.get()
        }
        KuGouDiagnostics.debug(
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
                matchesCurrentTrack(metadata, publication.track, hostPackage) &&
                overlay(metadata, publication, hostPackage) == HostMetadataOverlay.Result.WRITTEN
            ) {
                KuGouDiagnostics.debug(
                    area = "publisher",
                    event = "NATIVE_METADATA_INTERCEPTED",
                    generation = publication.generation,
                    message = "source=${publication.source}"
                )
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
            KuGouDiagnostics.error(
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
     * the lyricInfo KuGou put on this object, never from a payload the module wrote into it.
     * Returns null when nothing could be encoded.
     */
    internal fun overlay(
        metadata: MediaMetadata,
        publication: KuGouPublication,
        hostPackage: String
    ): HostMetadataOverlay.Result? {
        val existing = HostMetadataOverlay.hostLyricInfo(metadata)
        val encoded = KuGouOfficialLyricInfoEncoder.encode(
            track = publication.track,
            lines = publication.lines,
            trackGeneration = publication.generation,
            hostPackage = hostPackage,
            existingLyricInfo = existing
        ) ?: return null
        val fingerprint = publication.track.id.orEmpty() + ':' +
            publication.generation + ':' + encoded.value.hashCode()
        if (fingerprint == lastPublishedFingerprint &&
            metadata.getString(KuGouPlayerConstants.METADATA_KEY_LYRIC_INFO) == encoded.value
        ) {
            return HostMetadataOverlay.Result.UNCHANGED
        }
        val result = HostMetadataOverlay.putLyricInfo(metadata, encoded.value).result
        if (!result.isApplied) {
            KuGouDiagnostics.debug(
                area = "publisher",
                event = "LYRIC_INFO_APPEND_SKIPPED",
                generation = publication.generation,
                payloadChars = encoded.value.length,
                reason = result.name
            )
            return result
        }
        lastPublishedFingerprint = fingerprint
        KuGouDiagnostics.debug(
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
        return matchesPublication(
            hostPackage = hostPackage,
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
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            mediaId = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.description.mediaId
            ),
            songId = KuGouOfficialLyricInfoEncoder.extractJsonString(lyricInfo.orEmpty(), "songId"),
            track = track
        )
    }

    internal fun matchesPublication(
        hostPackage: String,
        title: String?,
        artist: String?,
        album: String? = null,
        durationMs: Long = 0L,
        mediaId: String? = null,
        songId: String?,
        track: TrackIdentity
    ): Boolean {
        if (!track.id.isNullOrBlank() && !songId.isNullOrBlank() && track.id == songId) {
            return true
        }
        val hostTrack = KuGouTrackIdentity.sanitize(
            hostPackage = hostPackage,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            mediaId = mediaId,
            songIdFromLyricInfo = songId
        )
        if (!track.id.isNullOrBlank() && !hostTrack.id.isNullOrBlank() &&
            track.id == hostTrack.id
        ) {
            return true
        }
        if (TrackIdentityPolicy.isSameTrack(track, hostTrack) ||
            KuGouTrackIdentity.trackKey(track.title, track.artist) ==
            KuGouTrackIdentity.trackKey(hostTrack.title, hostTrack.artist)
        ) {
            return true
        }
        return KuGouPlayerConstants.isLite(hostPackage) &&
            KuGouMetadataIdentityPolicy.looksLikeCarLyricDisplayMetadata(
                title = title,
                artist = artist,
                currentTitle = track.title,
                currentArtist = track.artist
            )
    }

    internal fun sessionTag(session: MediaSession?): String? {
        if (session == null) return null
        return runCatching {
            var type: Class<*>? = session.javaClass
            while (type != null) {
                val current = type
                val field = runCatching { current.getDeclaredField("mTag") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.get(session) as? String
                }
                type = current.superclass
            }
            null
        }.getOrNull()
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private data class ReplayRequest(
        val session: MediaSession,
        val metadata: MediaMetadata,
        val generation: Long
    )
}
