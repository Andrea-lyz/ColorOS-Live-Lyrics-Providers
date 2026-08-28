/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import java.lang.ref.WeakReference

class LxMediaSessionRegistry {
    private data class Entry(
        val session: WeakReference<Any>,
        val tag: String,
        var track: TrackIdentity? = null,
        var hostMetadata: Any? = null,
        var playbackState: Int = PlaybackState.STATE_NONE,
        var active: Boolean = false,
        var released: Boolean = false
    )

    private val entries = mutableListOf<Entry>()
    private val moduleWrite = ThreadLocal<Boolean>()

    @Synchronized
    fun onConstructed(session: Any, tag: String?) {
        prune()
        if (entry(session) == null) entries += Entry(WeakReference(session), tag.orEmpty())
    }

    @Synchronized
    fun onHostMetadata(session: Any, track: TrackIdentity?, metadata: Any? = null) {
        ensure(session).apply {
            this.track = track?.takeUnless { it.isBlank }
            this.hostMetadata = metadata
        }
    }

    @Synchronized
    fun onPlaybackState(session: Any, state: Int) {
        ensure(session).playbackState = state
    }

    @Synchronized
    fun onActive(session: Any, active: Boolean) {
        ensure(session).active = active
    }

    @Synchronized
    fun onReleased(session: Any) {
        entry(session)?.released = true
    }

    @Synchronized
    fun selectUnique(track: TrackIdentity): Any? {
        prune()
        if (track.isBlank) return uniqueLiveSessionLocked()
        return LxMediaSessionSelectionPolicy.selectUnique(entries.mapNotNull { candidate ->
            candidate.session.get()?.let { session ->
                LxSessionCandidate(
                    session, candidate.tag, candidate.track, candidate.playbackState,
                    candidate.active, candidate.released
                )
            }
        }, track) ?: uniqueLiveSessionLocked()?.takeIf { session ->
            TrackIdentityPolicy.isSameTrack(entry(session)?.track, track)
        }
    }

    @Synchronized
    fun uniqueCurrentTrack(): TrackIdentity? {
        prune()
        val preferred = entries.filter {
            !it.released && it.active && isPlaybackStateValid(it.playbackState) && it.track != null
        }.singleOrNull()?.track
        if (preferred != null) return preferred
        return entries.filter { !it.released && it.track != null }.singleOrNull()?.track
    }

    @Synchronized
    fun uniqueLiveSession(): Any? {
        prune()
        return uniqueLiveSessionLocked()
    }

    @Synchronized
    fun hostMetadata(session: Any): Any? = entry(session)?.hostMetadata

    fun isModuleWrite(): Boolean = moduleWrite.get() == true

    fun <T> withModuleWrite(block: () -> T): T {
        val previous = moduleWrite.get() == true
        moduleWrite.set(true)
        return try {
            block()
        } finally {
            if (previous) moduleWrite.set(true) else moduleWrite.remove()
        }
    }

    @Synchronized
    private fun ensure(session: Any): Entry = entry(session) ?: Entry(
        session = WeakReference(session), tag = ""
    ).also(entries::add)

    private fun entry(session: Any): Entry? = entries.firstOrNull { it.session.get() === session }
    private fun prune() = entries.removeAll { it.session.get() == null || it.released }

    private fun uniqueLiveSessionLocked(): Any? {
        val preferred = entries.filter {
            !it.released && it.active && isPlaybackStateValid(it.playbackState)
        }.mapNotNull { it.session.get() }.singleOrNull()
        if (preferred != null) return preferred
        return entries.filter { !it.released }.mapNotNull { it.session.get() }.singleOrNull()
    }

    companion object {
        fun isPlaybackStateValid(state: Int): Boolean = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING || state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM

        fun trackFrom(metadata: MediaMetadata?): TrackIdentity? {
            if (metadata == null) return null
            return TrackIdentity(
                id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ).takeUnless { it.isBlank }
        }

        fun constructorTag(args: Array<out Any?>): String? = args.firstOrNull { it is String } as? String
    }
}

internal data class LxSessionCandidate(
    val session: Any,
    val constructorTag: String,
    val track: TrackIdentity?,
    val playbackState: Int,
    val active: Boolean,
    val released: Boolean
)

internal object LxMediaSessionSelectionPolicy {
    fun selectUnique(candidates: List<LxSessionCandidate>, track: TrackIdentity): Any? = candidates
        .filter { candidate ->
            !candidate.released && candidate.active &&
                LxMediaSessionRegistry.isPlaybackStateValid(candidate.playbackState) &&
                TrackIdentityPolicy.isSameTrack(candidate.track, track)
        }
        .map { it.session }
        .singleOrNull()
}

internal data class LxReplaySnapshot(
    val session: WeakReference<Any>,
    val track: TrackIdentity,
    val generation: Long,
    val publication: LxPublication
)

internal object LxReplayPolicy {
    fun ownedProviderFragment(hostPackage: String): String = "\"provider\":\"$hostPackage\""
    fun ownedSourceFragment(hostPackage: String): String = "\"source\":\"$hostPackage-v5\""

    fun isModuleOwned(payload: String?, hostPackage: String): Boolean = payload != null &&
        payload.contains(ownedProviderFragment(hostPackage)) &&
        payload.contains(ownedSourceFragment(hostPackage))

    fun shouldReplay(
        cached: LxReplaySnapshot?,
        selectedSession: Any?,
        incomingTrack: TrackIdentity?,
        currentTrack: TrackIdentity?,
        currentGeneration: Long,
        generationValid: Boolean,
        artworkReady: Boolean,
        incomingLyricInfo: String?
    ): Boolean {
        if (cached == null || selectedSession !== cached.session.get() || incomingTrack == null) return false
        if (!generationValid || cached.generation != currentGeneration) return false
        if (!LxBluetoothLyricMetadataPolicy.sameSong(cached.track, incomingTrack)) return false
        if (currentTrack != null && !LxBluetoothLyricMetadataPolicy.sameSong(cached.track, currentTrack)) {
            return false
        }
        if (currentTrack == null) return false
        if (!artworkReady) return false
        return incomingLyricInfo.isNullOrBlank()
    }
}
