/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import android.media.MediaMetadata
import android.media.session.MediaSession
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import java.lang.ref.WeakReference

/**
 * Overlays lyricInfo onto NetEase's platform MediaSession writes. Artwork
 * copies through an empty typed Builder. At most one replay per generation.
 */
object NeteaseLyricInfoPublisher {
    private val lock = Any()

    @Volatile
    private var selfPublishing = false
    private var latestPublication: NeteasePublication? = null
    private var lastSession: WeakReference<MediaSession>? = null
    private var lastHostMetadata: MediaMetadata? = null
    private var lastPublishedFingerprint = ""
    private var replayedGeneration = 0L
    private val blankOverlayLogs = java.util.concurrent.atomic.AtomicInteger(0)

    fun isSelfPublishing(): Boolean = selfPublishing

    fun onTrackChanged(generation: Long, incoming: TrackIdentity): Boolean {
        val kept: Boolean
        synchronized(lock) {
            val current = latestPublication
            if (current != null &&
                TrackIdentityPolicy.isSameTrack(current.track, incoming)
            ) {
                kept = true
            } else {
                kept = false
                latestPublication = null
                lastPublishedFingerprint = ""
                if (replayedGeneration != generation) {
                    replayedGeneration = 0L
                }
            }
        }
        NeteaseDiagnostics.debug(
            area = "publisher",
            event = if (kept) "TRACK_CHANGED_KEEP" else "TRACK_CHANGED",
            generation = generation
        )
        return kept
    }

    fun onLyricReady(publication: NeteasePublication) {
        synchronized(lock) {
            latestPublication = publication
        }
        NeteaseDiagnostics.debug(
            area = "publisher",
            event = "LYRIC_READY",
            generation = publication.generation,
            message = "lines=${publication.lines.size} mode=${publication.payloadMode.name} " +
                "capture=${publication.captureOrigin}"
        )
    }

    fun prepareHostMetadata(
        session: MediaSession?,
        metadata: MediaMetadata,
        hostPackage: String
    ): MediaMetadata {
        val prepared = synchronized(lock) {
            val currentMetadata = clearStaleModulePayload(metadata)
            if (session != null) {
                lastSession = WeakReference(session)
            }
            lastHostMetadata = currentMetadata
            val publication = latestPublication
            when {
                publication == null -> {
                    logOverlaySkipped(currentMetadata, reason = "no-publication", generation = null)
                    currentMetadata
                }
                !matchesCurrentTrack(currentMetadata, publication.track) -> {
                    logOverlaySkipped(
                        currentMetadata,
                        reason = "identity-mismatch",
                        generation = publication.generation,
                        extra = "pubId=${publication.track.id.orEmpty().take(32)} " +
                            "pubTitle=${publication.track.title.orEmpty().take(48)}"
                    )
                    currentMetadata
                }
                else -> runCatching {
                    overlay(currentMetadata, publication, hostPackage)
                }.onFailure { error ->
                    NeteaseDiagnostics.error(
                        area = "publisher",
                        event = "OVERLAY_FAILED",
                        message = error.message,
                        throwable = error
                    )
                }.getOrNull() ?: currentMetadata
            }
        }
        return prepared
    }

    fun replayIfNeeded(hostPackage: String): Boolean {
        val request = synchronized(lock) {
            if (selfPublishing) return false
            val publication = latestPublication ?: return false
            if (publication.generation == replayedGeneration) return false
            val session = lastSession?.get()
            if (session == null) {
                NeteaseDiagnostics.info(
                    area = "publisher",
                    event = "REPLAY_SKIPPED",
                    generation = publication.generation,
                    session = publication.track.id,
                    reason = "no-session"
                )
                return false
            }
            val metadata = lastHostMetadata
            if (metadata == null) {
                NeteaseDiagnostics.info(
                    area = "publisher",
                    event = "REPLAY_SKIPPED",
                    generation = publication.generation,
                    session = publication.track.id,
                    reason = "no-host-metadata"
                )
                return false
            }
            if (!matchesCurrentTrack(metadata, publication.track)) {
                NeteaseDiagnostics.info(
                    area = "publisher",
                    event = "REPLAY_SKIPPED",
                    generation = publication.generation,
                    session = publication.track.id,
                    reason = "identity-mismatch"
                )
                return false
            }
            val patched = runCatching {
                overlay(metadata, publication, hostPackage)
            }.onFailure { error ->
                NeteaseDiagnostics.error(
                    area = "publisher",
                    event = "OVERLAY_FAILED",
                    message = error.message,
                    throwable = error
                )
            }.getOrNull()
            if (patched == null) {
                NeteaseDiagnostics.info(
                    area = "publisher",
                    event = "REPLAY_SKIPPED",
                    generation = publication.generation,
                    session = publication.track.id,
                    reason = "overlay-null"
                )
                return false
            }
            if (patched.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO) ==
                metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO)
            ) {
                replayedGeneration = publication.generation
                NeteaseDiagnostics.info(
                    area = "publisher",
                    event = "REPLAY_SKIPPED",
                    generation = publication.generation,
                    session = publication.track.id,
                    reason = "unchanged"
                )
                return false
            }
            replayedGeneration = publication.generation
            ReplayRequest(session, patched, publication.generation)
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
            NeteaseDiagnostics.error(
                area = "publisher",
                event = "REPLAY_FAILED",
                message = it.message
            )
            false
        }.also {
            selfPublishing = false
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            latestPublication = null
            lastSession = null
            lastHostMetadata = null
            lastPublishedFingerprint = ""
            replayedGeneration = 0L
            selfPublishing = false
            blankOverlayLogs.set(0)
        }
    }

    internal fun overlay(
        metadata: MediaMetadata,
        publication: NeteasePublication,
        hostPackage: String
    ): MediaMetadata? {
        val existing = metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO)
        val encoded = runCatching {
            NeteaseLyricInfoPayloadEncoder.encode(
                track = publication.track,
                lines = publication.lines,
                trackGeneration = publication.generation,
                hostPackage = hostPackage,
                existingLyricInfo = existing,
                mode = publication.payloadMode
            )
        }.onFailure { error ->
            NeteaseDiagnostics.error(
                area = "publisher",
                event = "ENCODE_FAILED",
                message = error.message,
                throwable = error
            )
        }.getOrNull()
        if (encoded == null) {
            NeteaseDiagnostics.info(
                area = "publisher",
                event = "OVERLAY_SKIPPED",
                generation = publication.generation,
                session = publication.track.id,
                reason = "encode-null",
                message = "lines=${publication.lines.size} " +
                    "source=${publication.payloadMode.source}"
            )
            return null
        }
        val fingerprint = publication.track.id.orEmpty() + ':' +
            publication.generation + ':' + encoded.value.hashCode()
        if (fingerprint == lastPublishedFingerprint &&
            metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO) == encoded.value
        ) {
            return metadata
        }
        lastPublishedFingerprint = fingerprint
        val patched = NeteaseMetadataCopy.copyWithLyricInfo(metadata, encoded.value)
        NeteaseDiagnostics.info(
            area = "publisher",
            event = "LYRIC_INFO_PATCHED",
            generation = publication.generation,
            session = publication.track.id,
            payloadChars = encoded.value.length,
            message = "source=${publication.payloadMode.source} " +
                "capture=${publication.captureOrigin} rawChars=${encoded.rawLyric.length} " +
                "translationChars=${encoded.translationLyric.length} " +
                "officialLyricRepair=${encoded.repairedOfficialLyric}"
        )
        return patched
    }

    private fun clearStaleModulePayload(metadata: MediaMetadata): MediaMetadata {
        val existing = metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO)
        if (!NeteaseLyricInfoPayloadEncoder.isModulePayload(existing)) return metadata
        val hostTrack = TrackIdentity(
            id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ),
            artist = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            )
        )
        if (hostTrack.title.isNullOrBlank()) return metadata
        if (NeteaseLyricInfoPayloadEncoder.isModulePayloadForTrack(existing, hostTrack)) {
            return metadata
        }
        NeteaseDiagnostics.info(
            area = "publisher",
            event = "STALE_LYRICINFO_CLEARED",
            session = NeteaseLyricInfoPayloadEncoder.extractJsonString(
                existing.orEmpty(),
                "songId"
            ),
            reason = "metadata-track-mismatch",
            message = "hostIdentityPresent=${!hostTrack.isBlank}"
        )
        return NeteaseMetadataCopy.copyWithLyricInfo(metadata, "")
    }

    private fun logOverlaySkipped(
        metadata: MediaMetadata,
        reason: String,
        generation: Long?,
        extra: String? = null
    ) {
        val existing = metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO).orEmpty()
        if (existing.isBlank() && reason == "no-publication") {
            val count = blankOverlayLogs.incrementAndGet()
            if (count > NeteasePlayerConstants.BLANK_OVERLAY_LOG_LIMIT) {
                return
            }
            NeteaseDiagnostics.info(
                area = "publisher",
                event = "OVERLAY_SKIPPED",
                generation = generation,
                session = "blank-$count",
                reason = "no-publication-blank"
            )
            return
        }
        val provider = NeteaseLyricInfoPayloadEncoder.extractJsonString(existing, "provider")
        val rawChars = NeteaseLyricInfoPayloadEncoder.extractJsonString(existing, "rawLyric")?.length ?: 0
        NeteaseDiagnostics.info(
            area = "publisher",
            event = "OVERLAY_SKIPPED",
            generation = generation,
            session = reason,
            reason = reason,
            message = "lyricInfoChars=${existing.length} provider=${provider.orEmpty()} " +
                "rawChars=$rawChars" +
                if (extra.isNullOrBlank()) "" else " $extra"
        )
    }

    internal fun matchesCurrentTrack(
        metadata: MediaMetadata,
        track: TrackIdentity
    ): Boolean {
        val lyricInfo = metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO)
        val songId = NeteaseLyricInfoPayloadEncoder.extractJsonString(
            lyricInfo.orEmpty(),
            "songId"
        )
        if (!track.id.isNullOrBlank() && !songId.isNullOrBlank() && track.id == songId) {
            return true
        }
        val hostTrack = TrackIdentity(
            id = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                songId
            ),
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
