package io.github.andrealtb.coloroslyrics.provider.salt

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import java.lang.ref.WeakReference

class SaltMediaSessionRegistry {
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

    @Synchronized fun onConstructed(session: Any, tag: String?) {
        prune()
        if (entry(session) == null) entries += Entry(WeakReference(session), tag.orEmpty())
    }

    @Synchronized fun onHostMetadata(session: Any, track: TrackIdentity?, metadata: Any? = null) {
        ensure(session).apply {
            this.track = track?.takeUnless { it.isBlank }
            this.hostMetadata = metadata
        }
    }

    @Synchronized fun onPlaybackState(session: Any, state: Int) {
        ensure(session).playbackState = state
    }

    @Synchronized fun onActive(session: Any, active: Boolean) {
        ensure(session).active = active
    }

    @Synchronized fun onReleased(session: Any) {
        entry(session)?.released = true
    }

    @Synchronized fun selectUnique(track: TrackIdentity): Any? {
        prune()
        return SaltMediaSessionSelectionPolicy.selectUnique(entries.mapNotNull { candidate ->
            candidate.session.get()?.let { session ->
                SaltSessionCandidate(session, candidate.tag, candidate.track, candidate.playbackState,
                    candidate.active, candidate.released)
            }
        }, track)
    }

    @Synchronized fun uniqueCurrentTrack(): TrackIdentity? {
        prune()
        return entries.filter {
            !it.released && it.active && isPlaybackStateValid(it.playbackState) && it.track != null
        }.singleOrNull()?.track
    }

    @Synchronized fun hostMetadata(session: Any): Any? = entry(session)?.hostMetadata

    fun isModuleWrite(): Boolean = moduleWrite.get() == true

    fun <T> withModuleWrite(block: () -> T): T {
        val previous = moduleWrite.get() == true
        moduleWrite.set(true)
        return try { block() } finally {
            if (previous) moduleWrite.set(true) else moduleWrite.remove()
        }
    }

    @Synchronized private fun ensure(session: Any): Entry = entry(session) ?: Entry(
        session = WeakReference(session), tag = ""
    ).also(entries::add)

    private fun entry(session: Any): Entry? = entries.firstOrNull { it.session.get() === session }
    private fun prune() = entries.removeAll { it.session.get() == null || it.released }

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

internal data class SaltSessionCandidate(
    val session: Any,
    val constructorTag: String,
    val track: TrackIdentity?,
    val playbackState: Int,
    val active: Boolean,
    val released: Boolean
)

internal object SaltMediaSessionSelectionPolicy {
    fun selectUnique(candidates: List<SaltSessionCandidate>, track: TrackIdentity): Any? = candidates
        .filter { candidate ->
            !candidate.released && candidate.active &&
                SaltMediaSessionRegistry.isPlaybackStateValid(candidate.playbackState) &&
                TrackIdentityPolicy.isSameTrack(candidate.track, track)
        }
        .map { it.session }
        .singleOrNull()
}

internal data class SaltReplaySnapshot(
    val session: WeakReference<Any>,
    val track: TrackIdentity,
    val generation: Long,
    val publication: SaltPublication
)

internal object SaltReplayPolicy {
    const val OWNED_PROVIDER_FRAGMENT = "\"provider\":\"com.salt.music\""
    const val OWNED_SOURCE_FRAGMENT = "\"source\":\"com.salt.music-v5\""

    fun isModuleOwned(payload: String?): Boolean = payload?.contains(OWNED_PROVIDER_FRAGMENT) == true &&
        payload.contains(OWNED_SOURCE_FRAGMENT)

    fun shouldReplay(
        cached: SaltReplaySnapshot?,
        selectedSession: Any?,
        incomingTrack: TrackIdentity?,
        currentTrack: TrackIdentity?,
        currentGeneration: Long,
        generationValid: Boolean,
        incomingLyricInfo: String?
    ): Boolean {
        if (cached == null || selectedSession !== cached.session.get() || incomingTrack == null) return false
        if (!generationValid || cached.generation != currentGeneration) return false
        if (!TrackIdentityPolicy.isSameTrack(cached.track, incomingTrack) ||
            !TrackIdentityPolicy.isSameTrack(cached.track, currentTrack)) return false
        return incomingLyricInfo.isNullOrBlank()
    }
}
