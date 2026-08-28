/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

/** Owns track generation, decoded publications, deduplication, and replay. */
class NeteaseLyricSessionCoordinator(
    private val hostPackage: String,
    private val processName: String
) {
    private val stateLock = Any()
    private val generationPolicy = TrackGenerationPolicy()

    @Volatile
    private var currentTrack: TrackIdentity? = null

    @Volatile
    private var currentGeneration = 0L

    private var lastEmittedSignature = ""
    private var lastLyricReadyGeneration = 0L

    fun captureOfficial(
        snapshot: NeteaseLyricInfoReader.Snapshot,
        musicInfoPresent: Boolean,
        captureOrigin: String
    ): NeteasePublication? {
        val track = if (snapshot.track.id.isNullOrBlank()) {
            snapshot.track.copy(id = snapshot.lyricMusicId)
        } else {
            snapshot.track
        }
        NeteaseDiagnostics.info(
            area = "lyric",
            event = "LYRIC_WRITE_SEEN",
            process = processName,
            reason = when {
                snapshot.idsMatch -> "ids-match"
                !musicInfoPresent -> "lyric-only"
                else -> "id-mismatch"
            },
            session = track.id,
            message = "source=$captureOrigin ${describeSnapshot(snapshot)}"
        )
        if (musicInfoPresent && !snapshot.idsMatch) {
            NeteaseDiagnostics.info(
                area = "lyric",
                event = "LYRIC_CANDIDATE_REJECTED",
                process = processName,
                reason = "id-mismatch",
                session = track.id,
                message = describeSnapshot(snapshot)
            )
            return null
        }
        bindTrack(track, captureOrigin)
        val lines = NeteaseLyricDecoder.decode(snapshot)
        if (lines.isEmpty()) {
            NeteaseDiagnostics.info(
                area = "lyric",
                event = "LYRIC_DECODE_EMPTY",
                process = processName,
                reason = "no-lines",
                session = track.id,
                message = describeSnapshot(snapshot)
            )
        }
        return emitLyrics(
            lines = lines,
            track = track,
            captureOrigin = captureOrigin,
            payloadMode = NeteasePayloadMode.OFFICIAL_APPEND
        )
    }

    fun emitConstructed(
        lines: List<RichLyricLine>,
        track: TrackIdentity,
        captureOrigin: String
    ): NeteasePublication? = emitLyrics(
        lines = lines,
        track = track,
        captureOrigin = captureOrigin,
        payloadMode = NeteasePayloadMode.CONSTRUCTED
    )

    fun bindTrack(track: TrackIdentity, reason: String): NeteaseTrackSnapshot {
        if (track.isBlank) return currentSnapshot()
        val generation = generationPolicy.onTrackObserved(track)
        val changed: Boolean
        synchronized(stateLock) {
            changed = generation != currentGeneration || currentTrack?.id != track.id
            currentTrack = mergeTrack(currentTrack, track)
            currentGeneration = generation
            if (changed) {
                lastEmittedSignature = ""
                lastLyricReadyGeneration = 0L
            }
        }
        if (changed) {
            NeteaseLyricInfoPublisher.onTrackChanged(generation, track)
            NeteaseDiagnostics.info(
                area = "identity",
                event = "TRACK_BOUND",
                process = processName,
                generation = generation,
                session = track.id,
                reason = reason,
                message = "title=${track.title.orEmpty().take(64)} id=${track.id.orEmpty().take(48)}"
            )
        }
        return currentSnapshot()
    }

    fun currentSnapshot(): NeteaseTrackSnapshot = synchronized(stateLock) {
        NeteaseTrackSnapshot(currentTrack, currentGeneration)
    }

    fun describeSnapshot(snapshot: NeteaseLyricInfoReader.Snapshot): String =
        "idsMatch=${snapshot.idsMatch} " +
            "filter=${snapshot.track.id.orEmpty().take(32)} " +
            "lyricId=${snapshot.lyricMusicId.orEmpty().take(32)} " +
            "title=${snapshot.track.title.orEmpty().take(48)} " +
            "yrc=${snapshot.yrc?.length ?: 0} lrc=${snapshot.lrc?.length ?: 0} " +
            "yrcTr=${snapshot.yrcTranslate?.length ?: 0} " +
            "lrcTr=${snapshot.lrcTranslate?.length ?: 0} " +
            threadNote()

    private fun emitLyrics(
        lines: List<RichLyricLine>,
        track: TrackIdentity,
        captureOrigin: String,
        payloadMode: NeteasePayloadMode
    ): NeteasePublication? {
        if (lines.isEmpty() || track.isBlank) {
            NeteaseDiagnostics.info(
                area = "lyric",
                event = "LYRIC_EMIT_SKIPPED",
                process = processName,
                reason = if (lines.isEmpty()) "empty-lines" else "blank-track",
                session = track.id,
                message = "title=${track.title.orEmpty().take(48)} lines=${lines.size} " +
                    threadNote()
            )
            return null
        }
        val snapshot = currentSnapshot()
        if (snapshot.generation <= 0L) {
            NeteaseDiagnostics.info(
                area = "lyric",
                event = "LYRIC_EMIT_SKIPPED",
                process = processName,
                reason = "no-generation",
                session = track.id,
                message = "title=${track.title.orEmpty().take(48)} ${threadNote()}"
            )
            return null
        }
        val boundId = snapshot.track?.id
        if (!track.id.isNullOrBlank() && !boundId.isNullOrBlank() && track.id != boundId) {
            NeteaseDiagnostics.info(
                area = "lyric",
                event = "LYRIC_CANDIDATE_REJECTED",
                process = processName,
                generation = snapshot.generation,
                reason = "foreign",
                session = track.id,
                message = "incoming=${track.id} bound=$boundId ${threadNote()}"
            )
            return null
        }
        val signature = buildSignature(track, lines)
        synchronized(stateLock) {
            if (lastLyricReadyGeneration == snapshot.generation &&
                lastEmittedSignature == signature
            ) {
                NeteaseDiagnostics.info(
                    area = "lyric",
                    event = "LYRIC_EMIT_REUSED",
                    process = processName,
                    generation = snapshot.generation,
                    session = track.id,
                    reason = captureOrigin,
                    message = "lines=${lines.size} ${threadNote()}"
                )
                return NeteasePublication(
                    track,
                    lines,
                    snapshot.generation,
                    captureOrigin,
                    payloadMode
                )
            }
            lastLyricReadyGeneration = snapshot.generation
            lastEmittedSignature = signature
        }
        val publication = NeteasePublication(
            track,
            lines,
            snapshot.generation,
            captureOrigin,
            payloadMode
        )
        NeteaseLyricInfoPublisher.onLyricReady(publication)
        val replayed = runCatching {
            NeteaseLyricInfoPublisher.replayIfNeeded(hostPackage)
        }.onFailure { error ->
            NeteaseDiagnostics.error(
                area = "publisher",
                event = "REPLAY_FAILED",
                process = processName,
                message = "generation=${snapshot.generation} ${error.message}",
                throwable = error
            )
        }.getOrDefault(false)
        NeteaseDiagnostics.info(
            area = "publisher",
            event = if (replayed) "NATIVE_LYRICINFO_PATCHED" else "LYRIC_READY_WAITING_HOST",
            process = processName,
            generation = snapshot.generation,
            session = track.id,
            message = "source=${payloadMode.source} capture=$captureOrigin lines=${lines.size} " +
                "translated=${lines.count { !it.secondary.isNullOrBlank() }} replay=$replayed " +
                threadNote()
        )
        return publication
    }

    private fun mergeTrack(current: TrackIdentity?, incoming: TrackIdentity): TrackIdentity {
        if (current == null) return incoming
        return TrackIdentity(
            id = firstNonBlank(incoming.id, current.id),
            title = firstNonBlank(incoming.title, current.title),
            artist = firstNonBlank(incoming.artist, current.artist),
            album = firstNonBlank(incoming.album, current.album),
            durationMs = if (incoming.durationMs > 0L) incoming.durationMs else current.durationMs
        )
    }

    private fun buildSignature(track: TrackIdentity, lines: List<RichLyricLine>): String {
        val first = lines.firstOrNull()
        val last = lines.lastOrNull()
        return listOf(
            track.id.orEmpty(),
            lines.size.toString(),
            first?.begin?.toString().orEmpty(),
            first?.text.orEmpty(),
            last?.text.orEmpty(),
            lines.count { !it.secondary.isNullOrBlank() }.toString()
        ).joinToString("|")
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun threadNote(): String = "tid=" + android.os.Process.myTid()
}

data class NeteaseTrackSnapshot(
    val track: TrackIdentity?,
    val generation: Long
)
