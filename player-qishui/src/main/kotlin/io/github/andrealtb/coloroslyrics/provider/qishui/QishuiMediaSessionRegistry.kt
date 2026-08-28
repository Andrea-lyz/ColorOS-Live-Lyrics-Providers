/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import java.lang.ref.WeakReference
import java.util.WeakHashMap

class QishuiMediaSessionRegistry {
    private data class Entry(
        var tag: String? = null,
        var track: TrackIdentity? = null,
        var playbackState: Int = PlaybackState.STATE_NONE,
        var active: Boolean = false,
        var released: Boolean = false
    )

    private val entries = WeakHashMap<MediaSession, Entry>()
    private val moduleWrite = ThreadLocal<Boolean>()
    private var authoritative = WeakReference<MediaSession>(null)

    @Synchronized
    fun onConstructed(session: MediaSession, tag: String?) {
        entries.getOrPut(session, ::Entry).tag = tag
    }

    @Synchronized
    fun onHostMetadata(
        session: MediaSession,
        track: TrackIdentity?,
        metadata: MediaMetadata
    ) {
        val entry = entries.getOrPut(session, ::Entry)
        entry.track = track
        if (track != null && !entry.released && !QishuiPlayerConstants.isCastSessionTag(entry.tag)) {
            authoritative = WeakReference(session)
        }
    }

    @Synchronized
    fun onPlaybackState(session: MediaSession, state: Int) {
        entries.getOrPut(session, ::Entry).playbackState = state
    }

    @Synchronized
    fun onActive(session: MediaSession, active: Boolean) {
        entries.getOrPut(session, ::Entry).active = active
    }

    @Synchronized
    fun onReleased(session: MediaSession) {
        entries[session]?.released = true
        if (authoritative.get() === session) authoritative.clear()
    }

    @Synchronized
    fun isCastSession(session: MediaSession): Boolean =
        QishuiPlayerConstants.isCastSessionTag(entries[session]?.tag)

    @Synchronized
    fun resolve(track: TrackIdentity): MediaSession? {
        val direct = authoritative.get()
        val directEntry = direct?.let(entries::get)
        if (direct != null &&
            directEntry?.released != true &&
            !QishuiPlayerConstants.isCastSessionTag(directEntry?.tag) &&
            TrackIdentityPolicy.isSameTrack(directEntry?.track, track)
        ) {
            return direct
        }
        val candidates = entries.filter { (session, entry) ->
            !entry.released &&
                !QishuiPlayerConstants.isCastSessionTag(entry.tag) &&
                TrackIdentityPolicy.isSameTrack(entry.track, track)
        }
        val selected = candidates.entries
            .sortedByDescending { it.value.active }
            .firstOrNull()
            ?.key
        if (selected != null) authoritative = WeakReference(selected)
        return selected
    }

    fun isModuleWrite(): Boolean = moduleWrite.get() == true

    fun <T> withModuleWrite(block: () -> T): T {
        val previous = moduleWrite.get()
        moduleWrite.set(true)
        return try {
            block()
        } finally {
            if (previous == null) moduleWrite.remove() else moduleWrite.set(previous)
        }
    }

    companion object {
        fun constructorTag(args: Array<out Any?>): String? =
            args.firstOrNull { it is String } as? String
    }
}
