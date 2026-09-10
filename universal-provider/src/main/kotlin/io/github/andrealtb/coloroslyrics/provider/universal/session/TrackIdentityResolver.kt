/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.universal.session

class TrackIdentityResolver {
    private data class SessionLock(
        var generation: Long = 0L,
        var metadataRevision: Long = 0L,
        var title: String? = null,
        var artist: String? = null,
        var album: String? = null,
        var durationMs: Long? = null,
        var hostMediaId: String? = null,
        var playbackQueueItemId: Long? = null,
        var playbackExtrasMediaId: String? = null,
        var mediaUri: String? = null,
        var titlePolluted: Boolean = false,
        var demuxSource: String = "standard"
    )

    private val locks = LinkedHashMap<String, SessionLock>()

    @Synchronized
    fun observe(observation: SessionObservation): ResolvedSession {
        val demuxed = BluetoothLyricDemux.demux(observation.raw)
        val lock = locks.getOrPut(observation.sessionInstanceId) { SessionLock() }
        lock.metadataRevision += 1L

        val sameMediaId = sameFilled(lock.hostMediaId, demuxed.hostMediaId)
        val samePlaybackMediaId = sameFilled(lock.playbackExtrasMediaId, demuxed.playbackExtrasMediaId)
        val sameQueueId = lock.playbackQueueItemId != null && demuxed.playbackQueueItemId != null &&
            lock.playbackQueueItemId == demuxed.playbackQueueItemId
        val sameMediaUri = sameFilled(lock.mediaUri, demuxed.mediaUri)
        val hardIdentityChanged = (lock.hostMediaId != null && demuxed.hostMediaId != null && !sameMediaId) ||
            (lock.playbackExtrasMediaId != null && demuxed.playbackExtrasMediaId != null && !samePlaybackMediaId) ||
            (lock.mediaUri != null && demuxed.mediaUri != null && !sameMediaUri)
        val sameAnchor = sameAnchor(lock, demuxed)
        val sameResolved = sameText(lock.title, demuxed.title) && sameText(lock.artist, demuxed.artist)
        val sameTrack = when {
            lock.generation == 0L -> false
            hardIdentityChanged -> false
            sameMediaId && lock.hostMediaId != null && demuxed.hostMediaId != null -> true
            samePlaybackMediaId && lock.playbackExtrasMediaId != null && demuxed.playbackExtrasMediaId != null -> true
            sameQueueId -> true
            sameMediaUri && lock.mediaUri != null && demuxed.mediaUri != null -> true
            sameAnchor && (demuxed.titlePolluted || lock.titlePolluted) -> true
            // Bluetooth lyric metadata often restores the clean title before
            // album metadata catches up. During that polluted -> clean
            // transition, duration is the stable anchor; do not create a new
            // generation merely because album is temporarily missing.
            lock.titlePolluted && !demuxed.titlePolluted && sameDurationAnchor(lock, demuxed) -> true
            sameResolved && sameAnchor -> true
            sameResolved && (lock.durationMs == null || demuxed.durationMs == null) -> true
            else -> false
        }

        if (!sameTrack) {
            lock.generation += 1L
            lock.title = if (demuxed.source == "polluted-unresolved") null else demuxed.title
            lock.artist = demuxed.artist
            lock.album = demuxed.album
            lock.durationMs = demuxed.durationMs
            lock.hostMediaId = demuxed.hostMediaId
            lock.playbackQueueItemId = demuxed.playbackQueueItemId
            lock.playbackExtrasMediaId = demuxed.playbackExtrasMediaId
            lock.mediaUri = demuxed.mediaUri
            lock.titlePolluted = demuxed.titlePolluted
            lock.demuxSource = demuxed.source
        } else {
            if (!demuxed.titlePolluted && !demuxed.title.isNullOrBlank()) {
                lock.title = demuxed.title
            }
            lock.artist = firstFilled(lock.artist, demuxed.artist)
            lock.album = firstFilled(lock.album, demuxed.album)
            if (lock.durationMs == null) lock.durationMs = demuxed.durationMs
            if (lock.hostMediaId == null) lock.hostMediaId = demuxed.hostMediaId
            if (lock.playbackQueueItemId == null) lock.playbackQueueItemId = demuxed.playbackQueueItemId
            if (lock.playbackExtrasMediaId == null) lock.playbackExtrasMediaId = demuxed.playbackExtrasMediaId
            if (lock.mediaUri == null) lock.mediaUri = demuxed.mediaUri
            lock.titlePolluted = lock.titlePolluted || demuxed.titlePolluted
            if (demuxed.source != "polluted-unresolved") {
                lock.demuxSource = demuxed.source
            }
        }

        val descriptor = TrackDescriptor(
            userId = observation.userId,
            ownerPackage = observation.ownerPackage,
            sessionInstanceId = observation.sessionInstanceId,
            hostMediaId = lock.hostMediaId,
            title = lock.title,
            artist = lock.artist,
            album = lock.album,
            durationMs = lock.durationMs,
            metadataRevision = lock.metadataRevision,
            trackGeneration = lock.generation,
            titlePolluted = lock.titlePolluted,
            rawTitle = demuxed.rawTitle,
            playbackState = observation.playbackState,
            playing = observation.playing,
            playbackQueueItemId = lock.playbackQueueItemId,
            playbackExtrasMediaId = lock.playbackExtrasMediaId,
            mediaUri = lock.mediaUri,
            positionMs = demuxed.positionMs
        )
        return ResolvedSession(
            observation = observation,
            descriptor = descriptor,
            demuxSource = lock.demuxSource,
            identityComplete = !lock.title.isNullOrBlank() && !lock.artist.isNullOrBlank()
        )
    }

    @Synchronized
    fun drop(sessionInstanceId: String) {
        locks.remove(sessionInstanceId)
    }

    @Synchronized
    fun reset() {
        locks.clear()
    }

    private fun isLyricChurn(lock: SessionLock, demuxed: BluetoothLyricDemux.Result): Boolean {
        if (lock.generation == 0L) return false
        if (lock.title != null && demuxed.title != null && !sameText(lock.title, demuxed.title)) {
            return false
        }
        if (!sameAnchor(lock, demuxed)) return false
        if (demuxed.titlePolluted && (demuxed.title == null || sameText(lock.title, demuxed.title))) return true
        if (lock.titlePolluted && !sameText(lock.title, demuxed.title)) return true
        return false
    }

    private fun sameAnchor(lock: SessionLock, demuxed: BluetoothLyricDemux.Result): Boolean {
        val durationSame = sameDurationAnchor(lock, demuxed)
        val albumSame = lock.album.isNullOrBlank() ||
            demuxed.album.isNullOrBlank() ||
            sameText(lock.album, demuxed.album)
        return durationSame && albumSame
    }

    private fun sameDurationAnchor(lock: SessionLock, demuxed: BluetoothLyricDemux.Result): Boolean =
        BluetoothLyricDemux.sameDuration(lock.durationMs, demuxed.durationMs) ||
            lock.durationMs == null || demuxed.durationMs == null

    private fun sameFilled(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return sameText(left, right)
    }

    private fun firstFilled(previous: String?, incoming: String?): String? =
        previous?.trim()?.takeIf { it.isNotEmpty() } ?: incoming?.trim()?.takeIf { it.isNotEmpty() }

    private fun sameText(left: String?, right: String?): Boolean =
        left?.trim()?.equals(right?.trim(), ignoreCase = true) == true
}
