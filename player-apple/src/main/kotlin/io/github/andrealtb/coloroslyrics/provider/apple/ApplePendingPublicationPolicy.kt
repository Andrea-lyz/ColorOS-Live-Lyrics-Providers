/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

object ApplePendingPublicationPolicy {
    enum class Decision {
        PUBLISH,
        PENDING,
        DROP_STALE
    }

    fun decide(
        publicationTrack: TrackIdentity,
        currentHostTrack: TrackIdentity?,
        liveSessionTrack: TrackIdentity?,
        generationValid: Boolean,
        uniqueSessionReady: Boolean,
        metadataReady: Boolean,
        artworkReady: Boolean
    ): Decision {
        val hinted = !publicationTrack.isBlank
        if (hinted) {
            if (currentHostTrack == null) return Decision.PENDING
            if (!TrackIdentityPolicy.isSameTrack(publicationTrack, currentHostTrack)) {
                return Decision.DROP_STALE
            }
            if (!generationValid) return Decision.DROP_STALE
            if (!uniqueSessionReady || !metadataReady || !artworkReady) return Decision.PENDING
            if (!AppleTrackBindPolicy.unnamedOrSame(liveSessionTrack, publicationTrack)) {
                return Decision.PENDING
            }
            return Decision.PUBLISH
        }

        if (currentHostTrack == null || !generationValid || !uniqueSessionReady ||
            !metadataReady || !artworkReady
        ) {
            return Decision.PENDING
        }
        if (!AppleTrackBindPolicy.unnamedOrSame(liveSessionTrack, currentHostTrack)) {
            return Decision.PENDING
        }
        return Decision.PUBLISH
    }
}

class ApplePendingPublicationStore {
    private var pending: ApplePublication? = null

    @Synchronized
    fun replace(publication: ApplePublication): ApplePublication? {
        val previous = pending
        pending = publication
        return previous
    }

    @Synchronized
    fun peek(): ApplePublication? = pending

    @Synchronized
    fun takeIfSame(expected: ApplePublication): Boolean {
        if (pending === expected) {
            pending = null
            return true
        }
        return false
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}
