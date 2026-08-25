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

/**
 * Resolves the stable song identity carried by Salt's single playback MediaSession.
 *
 * With car/Bluetooth lyrics enabled Salt deliberately publishes the current lyric
 * line through TITLE and encodes the real identity as "artist - title" in ARTIST.
 * Newer builds may additionally retain the normal identity in DISPLAY_TITLE and
 * DISPLAY_SUBTITLE. Dynamic relay fields must never drive track generation.
 */
internal object SaltBluetoothLyricRelayPolicy {
    private val RELAY_SEPARATORS = arrayOf(" - ", " – ", " — ")

    data class RelayIdentity(val title: String, val artist: String)

    data class ResolvedIdentity(
        val track: TrackIdentity,
        val relay: Boolean,
        val source: String
    )

    fun resolve(metadata: MediaMetadata?): ResolvedIdentity? {
        if (metadata == null) return null
        return resolveFields(
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            displaySubtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    internal fun resolveFields(
        mediaId: String?,
        title: String?,
        artist: String?,
        displayTitle: String?,
        displaySubtitle: String?,
        album: String?,
        durationMs: Long
    ): ResolvedIdentity? {
        val relayIdentity = parseRelayIdentity(artist)
        val stableDisplayTitle = displayTitle?.trim().orEmpty()
        val stableDisplayArtist = displaySubtitle?.trim().orEmpty()

        val resolvedTitle: String
        val resolvedArtist: String
        val relay: Boolean
        val source: String

        if (stableDisplayTitle.isNotEmpty() && stableDisplayArtist.isNotEmpty()) {
            resolvedTitle = stableDisplayTitle
            resolvedArtist = stableDisplayArtist
            relay = relayIdentity != null && !sameText(title, stableDisplayTitle)
            source = "display"
        } else if (relayIdentity != null && !sameText(title, relayIdentity.title)) {
            resolvedTitle = relayIdentity.title
            resolvedArtist = relayIdentity.artist
            relay = true
            source = "relay-artist"
        } else {
            resolvedTitle = title?.trim().orEmpty()
            resolvedArtist = artist?.trim().orEmpty()
            relay = false
            source = "standard"
        }

        val track = TrackIdentity(
            id = mediaId?.trim()?.takeIf { it.isNotEmpty() },
            title = resolvedTitle.takeIf { it.isNotEmpty() },
            artist = resolvedArtist.takeIf { it.isNotEmpty() },
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = durationMs.coerceAtLeast(0L)
        )
        if (track.isBlank) return null
        return ResolvedIdentity(track, relay, source)
    }

    fun parseRelayIdentity(compositeArtist: String?): RelayIdentity? {
        val value = compositeArtist?.trim().orEmpty()
        var separatorIndex = -1
        var matchedSeparator: String? = null
        for (separator in RELAY_SEPARATORS) {
            val candidateIndex = value.indexOf(separator)
            if (candidateIndex > 0 && (separatorIndex < 0 || candidateIndex < separatorIndex)) {
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

    fun logResolved(resolved: ResolvedIdentity) {
        if (!resolved.relay) return
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/salt",
                area = "relay",
                event = "SALT_RELAY_IDENTITY_RESOLVED",
                trackHash = DiagnosticHasher.sha256(resolved.track.buildStableKey()),
                reason = resolved.source
            )
        )
    }

    private fun sameText(left: String?, right: String?): Boolean =
        left?.trim()?.equals(right?.trim(), ignoreCase = true) == true
}
