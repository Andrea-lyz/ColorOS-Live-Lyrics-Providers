/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

object PowerampPendingPublicationPolicy {
    enum class Decision {
        PUBLISH,
        PENDING,
        DROP_STALE
    }

    fun decide(
        publicationTrack: TrackIdentity,
        currentHostTrack: TrackIdentity?,
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
            return Decision.PUBLISH
        }

        if (currentHostTrack == null || !generationValid || !uniqueSessionReady ||
            !metadataReady || !artworkReady
        ) {
            return Decision.PENDING
        }
        return Decision.PUBLISH
    }
}

class PowerampPendingPublicationStore {
    private var pending: PowerampPublication? = null

    @Synchronized
    fun replace(publication: PowerampPublication): PowerampPublication? {
        val previous = pending
        pending = publication
        return previous
    }

    @Synchronized
    fun peek(): PowerampPublication? = pending

    @Synchronized
    fun take(): PowerampPublication? {
        val current = pending
        pending = null
        return current
    }

    @Synchronized
    fun takeIfSame(expected: PowerampPublication): Boolean {
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
