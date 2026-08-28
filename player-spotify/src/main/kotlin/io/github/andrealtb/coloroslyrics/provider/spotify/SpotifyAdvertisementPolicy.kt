/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import android.media.MediaMetadata
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import java.util.Locale

object SpotifyAdvertisementPolicy {
    fun isAdvertisement(
        title: String?,
        artist: String?,
        advertisementFlag: Long = 0L
    ): Boolean {
        if (advertisementFlag != 0L) return true
        val metadata = ((title ?: "") + " " + (artist ?: "")).lowercase(Locale.ROOT)
        return metadata.contains("广告")
            || metadata.contains("骞垮憡")
            || metadata.contains("advertisement")
            || metadata.contains("sponsored")
    }

    fun isAdvertisement(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        return isAdvertisement(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            advertisementFlag = metadata.getLong(SpotifyPlayerConstants.METADATA_KEY_ADVERTISEMENT)
        )
    }
}
