/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

class LxHostGenerationController(
    val policy: TrackGenerationPolicy = TrackGenerationPolicy(),
    private val onTrackChanged: (previous: TrackIdentity?, current: TrackIdentity) -> Unit = { _, _ -> }
) {
    fun observeTrack(track: TrackIdentity): Long {
        val previous = policy.currentTrack
        val generation = policy.onTrackObserved(track)
        if (previous != null && !TrackIdentityPolicy.isSameTrack(previous, track)) {
            onTrackChanged(previous, track)
        }
        return generation
    }

    fun acceptsPublication(track: TrackIdentity, generation: Long = policy.generation): Boolean =
        policy.isGenerationValid(generation) && TrackIdentityPolicy.isSameTrack(policy.currentTrack, track)
}
