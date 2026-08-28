/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

object ConePendingPublicationPolicy {
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
        metadataReady: Boolean
    ): Decision {
        if (!generationValid) return Decision.DROP_STALE
        if (currentHostTrack != null && !TrackIdentityPolicy.isSameTrack(publicationTrack, currentHostTrack)) {
            return Decision.DROP_STALE
        }
        if (!uniqueSessionReady || !metadataReady) return Decision.PENDING
        return Decision.PUBLISH
    }
}

class ConePendingPublicationStore {
    private var pending: ConePublication? = null

    @Synchronized
    fun replace(publication: ConePublication): ConePublication? {
        val previous = pending
        pending = publication
        return previous
    }

    @Synchronized
    fun peek(): ConePublication? = pending

    @Synchronized
    fun take(): ConePublication? {
        val current = pending
        pending = null
        return current
    }

    @Synchronized
    fun takeIfSame(expected: ConePublication): Boolean {
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
