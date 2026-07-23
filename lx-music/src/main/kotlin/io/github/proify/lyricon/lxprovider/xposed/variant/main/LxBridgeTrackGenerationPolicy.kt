/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

/**
 * Produces LX external-lyric generations that remain valid when the injected player process
 * restarts while SystemUI is still alive.
 *
 * SystemUI remembers the last generation per source, whereas this hook object's in-memory
 * counter starts over on each LX process launch. [SystemClock.elapsedRealtime] is monotonic for
 * the current boot, so it is newer than all previously published LX generations after a restart.
 */
object LxBridgeTrackGenerationPolicy {
    @JvmStatic
    fun next(previousGeneration: Long, elapsedRealtime: Long): Long {
        if (previousGeneration == Long.MAX_VALUE) return Long.MAX_VALUE

        val currentBootTime = maxOf(1L, elapsedRealtime)
        return maxOf(currentBootTime, previousGeneration + 1L)
    }
}
