/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import kotlin.math.max

object AppleArtworkPolicy {
    const val MIN_EDGE_PX = 8
    const val MAX_SESSION_EDGE_PX = 240

    fun isPlausibleBitmapSize(width: Int, height: Int): Boolean =
        width >= MIN_EDGE_PX && height >= MIN_EDGE_PX

    /**
     * Apple's first skip write is a real mzstatic https URI, not a Poweramp
     * `android.resource` placeholder. Waiting for the later Glide bitmap left
     * lockscreen empty on cold skips (`lyrics-log-20260828-002416.txt`).
     * Overlay onto whatever the host already published; later bitmap writes
     * still replay `lyricInfo`. No URI and no bitmap is native solid-color.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isReadyForLyricInfo(
        hasPlausibleBitmap: Boolean,
        artworkUris: Iterable<String?>
    ): Boolean = true

    fun shouldCopyForBinder(configName: String?, width: Int, height: Int): Boolean {
        if (!isPlausibleBitmapSize(width, height)) return false
        if (configName == "HARDWARE") return true
        return max(width, height) > MAX_SESSION_EDGE_PX
    }

    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_SESSION_EDGE_PX): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val longest = max(width, height)
        while (longest / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }
}
