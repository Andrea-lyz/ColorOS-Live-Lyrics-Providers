package io.github.proify.lyricon.qishuiprovider.xposed

import kotlin.math.roundToLong

internal data class QiShuiPlaybackSnapshot(
    val state: Int,
    val position: Long,
    val speed: Float,
    val lastPositionUpdateTime: Long,
    val moving: Boolean,
    val trackGeneration: Long,
    val sessionIdentity: Int,
    val observedAtElapsedMillis: Long
)

internal data class QiShuiTrackAuthority(
    val mediaId: String,
    val generation: Long
)

internal object QiShuiPlaybackPolicy {
    private const val MAX_PROJECTION_MS = 60_000L
    private const val PLAYBACK_LOG_DISCONTINUITY_MS = 2_000L

    fun isPlaybackProcess(packageName: String, processName: String): Boolean {
        return packageName.isNotBlank() && processName == packageName
    }

    fun acceptsOfficialCandidate(
        currentMediaId: String?,
        currentGeneration: Long,
        candidateMediaId: String?,
        observedGeneration: Long
    ): Boolean {
        return !currentMediaId.isNullOrBlank() &&
            currentMediaId == candidateMediaId &&
            currentGeneration > 0L &&
            currentGeneration == observedGeneration
    }

    fun nextGeneration(current: Long, nowElapsedMillis: Long): Long {
        val incremented = if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
        return maxOf(1L, incremented, nowElapsedMillis)
    }

    fun snapshotForCommit(
        snapshot: QiShuiPlaybackSnapshot?,
        trackGeneration: Long,
        sessionIdentity: Int
    ): QiShuiPlaybackSnapshot? {
        return snapshot?.takeIf {
            it.trackGeneration == trackGeneration && it.sessionIdentity == sessionIdentity
        }
    }

    fun projectPosition(
        snapshot: QiShuiPlaybackSnapshot,
        nowElapsedMillis: Long,
        duration: Long
    ): Long? {
        if (snapshot.position < 0L) return null
        val safeSpeed = snapshot.speed.takeIf(Float::isFinite) ?: 0f
        val elapsed = if (
            snapshot.moving &&
            snapshot.lastPositionUpdateTime > 0L &&
            nowElapsedMillis > snapshot.lastPositionUpdateTime
        ) {
            (nowElapsedMillis - snapshot.lastPositionUpdateTime).coerceAtMost(MAX_PROJECTION_MS)
        } else {
            0L
        }
        val projected = snapshot.position.toDouble() + elapsed.toDouble() * safeSpeed.toDouble()
        val rounded = when {
            projected.isNaN() -> snapshot.position
            projected >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            projected <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
            else -> projected.roundToLong()
        }
        return if (duration > 0L) rounded.coerceIn(0L, duration) else rounded.coerceAtLeast(0L)
    }

    fun shouldLogPlaybackSnapshot(
        previous: QiShuiPlaybackSnapshot?,
        current: QiShuiPlaybackSnapshot
    ): Boolean {
        if (previous == null) return true
        if (previous.trackGeneration != current.trackGeneration ||
            previous.sessionIdentity != current.sessionIdentity ||
            previous.state != current.state ||
            previous.moving != current.moving
        ) {
            return true
        }
        if (previous.position < 0L || current.position < 0L) {
            return previous.position != current.position
        }

        val expected = projectPosition(
            snapshot = previous,
            nowElapsedMillis = current.observedAtElapsedMillis,
            duration = 0L
        ) ?: return true
        val difference = if (current.position >= expected) {
            current.position - expected
        } else {
            expected - current.position
        }
        return difference >= PLAYBACK_LOG_DISCONTINUITY_MS
    }
}

internal object QiShuiResolutionPolicy {
    const val MAX_ATTEMPTS = 8
    const val NEGATIVE_CACHE_TTL_MS = 15_000L

    private val delaysBeforeAttempt = longArrayOf(
        0L,
        400L,
        700L,
        1_100L,
        1_700L,
        2_500L,
        3_500L,
        4_500L
    )

    fun delayBeforeAttempt(attempt: Int): Long? {
        return delaysBeforeAttempt.getOrNull(attempt)
    }
}

internal fun dispatchQiShuiSongCommit(
    setSong: () -> Unit,
    setPosition: (() -> Unit)?,
    replayPlaybackState: (() -> Unit)?,
    publishLyricReady: () -> Unit,
    publishPlaybackState: (() -> Unit)?
) {
    setSong()
    setPosition?.invoke()
    replayPlaybackState?.invoke()
    publishLyricReady()
    publishPlaybackState?.invoke()
}
