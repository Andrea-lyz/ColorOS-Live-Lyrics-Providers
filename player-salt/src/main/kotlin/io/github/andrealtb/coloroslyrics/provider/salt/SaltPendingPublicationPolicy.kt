package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

internal object SaltPendingPublicationPolicy {
    enum class Decision { PUBLISH, PENDING, DROP_STALE }

    fun decide(
        publicationTrack: TrackIdentity,
        currentHostTrack: TrackIdentity?,
        generationValid: Boolean,
        uniqueSessionReady: Boolean,
        metadataReady: Boolean
    ): Decision {
        if (currentHostTrack == null) return Decision.PENDING
        if (!TrackIdentityPolicy.isSameTrack(publicationTrack, currentHostTrack)) return Decision.DROP_STALE
        if (!generationValid) return Decision.DROP_STALE
        if (!uniqueSessionReady || !metadataReady) return Decision.PENDING
        return Decision.PUBLISH
    }
}

internal class SaltPendingPublicationStore {
    private var pending: SaltPublication? = null

    @Synchronized fun replace(publication: SaltPublication): SaltPublication? = pending.also {
        pending = publication
    }

    @Synchronized fun peek(): SaltPublication? = pending

    @Synchronized fun take(): SaltPublication? = pending.also { pending = null }

    @Synchronized fun takeIfSame(expected: SaltPublication): Boolean {
        if (pending !== expected) return false
        pending = null
        return true
    }

    @Synchronized fun clear(): SaltPublication? = take()
}
