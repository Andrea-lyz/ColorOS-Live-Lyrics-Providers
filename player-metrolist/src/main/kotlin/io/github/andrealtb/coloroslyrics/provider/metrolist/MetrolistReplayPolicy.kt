/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import java.lang.ref.WeakReference

internal data class MetrolistReplaySnapshot(
    val session: WeakReference<Any>,
    val track: TrackIdentity,
    val generation: Long,
    val publication: MetrolistPublication
)

internal object MetrolistReplayPolicy {
    fun ownedProviderFragment(hostPackage: String): String = "\"provider\":\"$hostPackage\""
    fun ownedSourceFragment(hostPackage: String): String = "\"source\":\"$hostPackage-v5\""

    fun isModuleOwned(payload: String?, hostPackage: String): Boolean = payload != null &&
        payload.contains(ownedProviderFragment(hostPackage)) &&
        payload.contains(ownedSourceFragment(hostPackage))

    fun shouldReplay(
        cached: MetrolistReplaySnapshot?,
        selectedSession: Any?,
        incomingTrack: TrackIdentity?,
        currentTrack: TrackIdentity?,
        currentGeneration: Long,
        generationValid: Boolean,
        artworkReady: Boolean,
        incomingLyricInfo: String?
    ): Boolean {
        if (cached == null || selectedSession !== cached.session.get() || incomingTrack == null) {
            return false
        }
        if (!generationValid || cached.generation != currentGeneration) return false
        if (!TrackIdentityPolicy.isSameTrack(cached.track, incomingTrack)) return false
        if (currentTrack != null && !TrackIdentityPolicy.isSameTrack(cached.track, currentTrack)) {
            return false
        }
        if (currentTrack == null) return false
        if (!artworkReady) return false
        return incomingLyricInfo.isNullOrBlank()
    }
}

