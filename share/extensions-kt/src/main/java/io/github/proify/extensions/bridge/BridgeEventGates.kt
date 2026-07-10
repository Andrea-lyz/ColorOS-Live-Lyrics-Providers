/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import kotlin.math.abs

class BridgePayloadGate(
    private val duplicateWindowMs: Long = 30_000L
) {
    private var lastKey = ""
    private var lastSentAtMillis = Long.MIN_VALUE

    @Synchronized
    fun shouldSend(key: String, nowMillis: Long): Boolean {
        if (key.isBlank()) return true
        val duplicate = key == lastKey && elapsedSince(lastSentAtMillis, nowMillis) < duplicateWindowMs
        if (duplicate) return false
        lastKey = key
        lastSentAtMillis = nowMillis
        return true
    }

    @Synchronized
    fun forget(key: String) {
        if (key == lastKey) {
            lastKey = ""
            lastSentAtMillis = Long.MIN_VALUE
        }
    }
}

class BridgePlaybackStateGate(
    private val positionToleranceMs: Long = 400L,
    private val heartbeatMs: Long = 10_000L
) {
    private var lastSnapshot: Snapshot? = null

    @Synchronized
    fun shouldSend(
        state: Int,
        position: Long,
        speed: Float,
        lastPositionUpdateTime: Long,
        moving: Boolean,
        generation: Long,
        nowElapsedMillis: Long,
        force: Boolean = false
    ): Boolean {
        val previous = lastSnapshot
        val shouldSend = force ||
            previous == null ||
            previous.state != state ||
            previous.generation != generation ||
            abs(previous.speed - speed) > SPEED_TOLERANCE ||
            projectedPositionDifference(
                previous,
                position,
                speed,
                lastPositionUpdateTime,
                moving,
                nowElapsedMillis
            ) > positionToleranceMs ||
            elapsedSince(previous.sentAtElapsedMillis, nowElapsedMillis) >= heartbeatMs

        if (shouldSend) {
            lastSnapshot = Snapshot(
                state = state,
                position = position,
                speed = speed,
                lastPositionUpdateTime = lastPositionUpdateTime,
                moving = moving,
                generation = generation,
                sentAtElapsedMillis = nowElapsedMillis
            )
        }
        return shouldSend
    }

    @Synchronized
    fun reset() {
        lastSnapshot = null
    }

    private fun projectedPositionDifference(
        previous: Snapshot,
        position: Long,
        speed: Float,
        lastPositionUpdateTime: Long,
        moving: Boolean,
        nowElapsedMillis: Long
    ): Long {
        if (previous.position < 0L || position < 0L) {
            return if (previous.position == position) 0L else Long.MAX_VALUE
        }
        val previousProjected = projectPosition(
            previous.position,
            previous.speed,
            previous.lastPositionUpdateTime,
            previous.moving,
            nowElapsedMillis
        )
        val currentProjected = projectPosition(
            position,
            speed,
            lastPositionUpdateTime,
            moving,
            nowElapsedMillis
        )
        return absoluteDifference(previousProjected, currentProjected)
    }

    private fun projectPosition(
        position: Long,
        speed: Float,
        lastPositionUpdateTime: Long,
        moving: Boolean,
        nowElapsedMillis: Long
    ): Long {
        if (!moving || lastPositionUpdateTime <= 0L || nowElapsedMillis <= lastPositionUpdateTime) {
            return position
        }
        val elapsed = (nowElapsedMillis - lastPositionUpdateTime).coerceAtMost(MAX_PROJECTION_MS)
        return position + (elapsed * speed).toLong()
    }

    private data class Snapshot(
        val state: Int,
        val position: Long,
        val speed: Float,
        val lastPositionUpdateTime: Long,
        val moving: Boolean,
        val generation: Long,
        val sentAtElapsedMillis: Long
    )

    private companion object {
        const val SPEED_TOLERANCE = 0.001f
        const val MAX_PROJECTION_MS = 60_000L
    }
}

private fun elapsedSince(previousMillis: Long, nowMillis: Long): Long {
    if (previousMillis == Long.MIN_VALUE || nowMillis < previousMillis) return Long.MAX_VALUE
    return nowMillis - previousMillis
}

private fun absoluteDifference(first: Long, second: Long): Long {
    if (first == second) return 0L
    val difference = if (first > second) first - second else second - first
    return if (difference < 0L) Long.MAX_VALUE else difference
}
