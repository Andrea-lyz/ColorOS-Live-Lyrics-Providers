package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

internal class SaltHostGenerationController(
    private val sessions: SaltMediaSessionRegistry,
    val policy: TrackGenerationPolicy = TrackGenerationPolicy(),
    private val onTrackChanged: () -> Unit = {}
) {
    fun observeUniqueHostMainTrack(): Long? {
        val track = sessions.uniqueCurrentTrack() ?: return null
        val previous = policy.currentTrack
        val generation = policy.onTrackObserved(track)
        if (previous != null && !TrackIdentityPolicy.isSameTrack(previous, track)) onTrackChanged()
        return generation
    }

    fun acceptsPublication(track: TrackIdentity, generation: Long = policy.generation): Boolean =
        policy.isGenerationValid(generation) && TrackIdentityPolicy.isSameTrack(policy.currentTrack, track)
}
