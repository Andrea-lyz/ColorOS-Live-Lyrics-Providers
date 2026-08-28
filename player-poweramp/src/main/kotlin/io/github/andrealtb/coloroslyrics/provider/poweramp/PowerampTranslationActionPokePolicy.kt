/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.media.session.PlaybackState

/**
 * ColorOS only rebinds the lockscreen favorite slot when it treats PlaybackState
 * as a new card. Poweramp skip often stays [PlaybackState.STATE_PLAYING] and only
 * writes metadata, so the Provider may poke once per track generation.
 *
 * Never poke a paused session, never delay, never poke from inside host
 * [MediaSession.setPlaybackState]: that combination wrote position=0 after pause
 * (`lyrics-log-20260826-070940.txt`).
 */
internal object PowerampTranslationActionPokePolicy {
    fun shouldPoke(
        isCastSession: Boolean,
        isModuleWrite: Boolean,
        isPokePass: Boolean,
        liveState: Int?,
        hasLyricInfo: Boolean,
        generation: Long,
        lastPokedGeneration: Long
    ): Boolean {
        if (isCastSession || isModuleWrite || isPokePass) return false
        if (!hasLyricInfo) return false
        if (liveState != PlaybackState.STATE_PLAYING) return false
        return generation != lastPokedGeneration
    }
}
