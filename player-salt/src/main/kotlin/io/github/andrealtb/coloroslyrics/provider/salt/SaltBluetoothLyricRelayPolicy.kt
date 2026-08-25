/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

/**
 * Ports the Bridge v4 Salt car/Bluetooth lyric relay rules.
 *
 * Salt periodically rewrites MediaSession metadata while a car/Bluetooth lyric
 * relay is active. The rewritten artist is `artist - title` and the rewritten
 * title is the current lyric line. The relay is associated with the last stable
 * full metadata instead of advancing the track generation.
 */
internal object SaltBluetoothLyricRelayPolicy {
    private val RELAY_SEPARATORS = arrayOf(" - ", " – ", " — ")

    data class RelayIdentity(val title: String, val artist: String)

    fun parseRelayIdentity(compositeArtist: String?): RelayIdentity? {
        if (compositeArtist == null) return null
        val value = compositeArtist.trim()
        var separatorIndex = -1
        var matchedSeparator: String? = null
        for (separator in RELAY_SEPARATORS) {
            val candidateIndex = value.indexOf(separator)
            if (candidateIndex > 0 &&
                (separatorIndex < 0 || candidateIndex < separatorIndex)
            ) {
                separatorIndex = candidateIndex
                matchedSeparator = separator
            }
        }
        if (separatorIndex < 0 || matchedSeparator == null) return null

        val artist = value.substring(0, separatorIndex).trim()
        val relayTitle = value.substring(separatorIndex + matchedSeparator.length).trim()
        if (artist.isEmpty() || relayTitle.isEmpty()) return null
        return RelayIdentity(relayTitle, artist)
    }

    fun matchesStable(
        stableTrack: TrackIdentity?,
        relayTitle: String,
        relayArtist: String,
        incomingDurationMs: Long
    ): Boolean {
        if (stableTrack == null || stableTrack.isBlank) return false
        val relayIdentity = TrackIdentity(
            title = relayTitle.takeIf { it.isNotBlank() },
            artist = relayArtist.takeIf { it.isNotBlank() },
            durationMs = incomingDurationMs
        )
        if (relayIdentity.isBlank) return false
        val sameTitleArtist = TrackIdentityPolicy.isSameTrack(
            stableTrack,
            relayIdentity
        )
        if (!sameTitleArtist) return false
        val stableDuration = stableTrack.durationMs
        return incomingDurationMs <= 0L || stableDuration <= 0L || incomingDurationMs == stableDuration
    }

    fun matchesStable(
        stable: MediaMetadata,
        relayTitle: String,
        relayArtist: String,
        incomingDurationMs: Long
    ): Boolean = matchesStable(
        SaltMediaSessionRegistry.trackFrom(stable),
        relayTitle,
        relayArtist,
        incomingDurationMs
    )

    fun logNormalized(track: TrackIdentity?, relay: RelayIdentity?) {
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/salt",
                area = "relay",
                event = "SALT_RELAY_METADATA_NORMALIZED",
                trackHash = track?.let {
                    DiagnosticHasher.sha256(it.buildStableKey())
                },
                reason = relay?.artist?.let { "artistHash=" + DiagnosticHasher.sha256(it) }
            )
        )
    }

    fun logNormalized(stable: MediaMetadata?, relay: RelayIdentity?) {
        logNormalized(stable?.let(SaltMediaSessionRegistry::trackFrom), relay)
    }

    fun logRejected(reason: String) {
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/salt",
                area = "relay",
                event = "SALT_RELAY_METADATA_REJECTED",
                reason = reason
            )
        )
    }
}
