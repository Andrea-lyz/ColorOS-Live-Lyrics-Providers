/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.media.session.PlaybackState
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerampTranslationActionPokePolicyTest {
    @Test
    fun pokesOncePerPlayingGenerationWithLyricInfo() {
        assertTrue(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = false,
                isModuleWrite = false,
                isPokePass = false,
                liveState = PlaybackState.STATE_PLAYING,
                hasLyricInfo = true,
                generation = 5L,
                lastPokedGeneration = 4L
            )
        )
        assertFalse(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = false,
                isModuleWrite = false,
                isPokePass = false,
                liveState = PlaybackState.STATE_PLAYING,
                hasLyricInfo = true,
                generation = 5L,
                lastPokedGeneration = 5L
            )
        )
    }

    @Test
    fun neverPokesPausedOrLyriclessOrUnsafePasses() {
        assertFalse(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = false,
                isModuleWrite = false,
                isPokePass = false,
                liveState = PlaybackState.STATE_PAUSED,
                hasLyricInfo = true,
                generation = 6L,
                lastPokedGeneration = 5L
            )
        )
        assertFalse(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = false,
                isModuleWrite = false,
                isPokePass = false,
                liveState = PlaybackState.STATE_PLAYING,
                hasLyricInfo = false,
                generation = 6L,
                lastPokedGeneration = 5L
            )
        )
        assertFalse(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = true,
                isModuleWrite = false,
                isPokePass = false,
                liveState = PlaybackState.STATE_PLAYING,
                hasLyricInfo = true,
                generation = 6L,
                lastPokedGeneration = 5L
            )
        )
        assertFalse(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = false,
                isModuleWrite = true,
                isPokePass = false,
                liveState = PlaybackState.STATE_PLAYING,
                hasLyricInfo = true,
                generation = 6L,
                lastPokedGeneration = 5L
            )
        )
        assertFalse(
            PowerampTranslationActionPokePolicy.shouldPoke(
                isCastSession = false,
                isModuleWrite = false,
                isPokePass = true,
                liveState = PlaybackState.STATE_PLAYING,
                hasLyricInfo = true,
                generation = 6L,
                lastPokedGeneration = 5L
            )
        )
    }
}
