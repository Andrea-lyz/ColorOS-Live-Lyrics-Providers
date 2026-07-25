/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge

import java.util.Locale

/**
 * Formats millisecond timestamps as the `[mm:ss.xxx]` segment used in plain LRC
 * and enhanced LRC documents produced by Providers.
 *
 * <p>Negative values are clamped to 0 so the renderer never sees a string like
 * `[-1,234.567]`. The implementation matches the historical per-Provider copy
 * to keep behavior identical to the existing inlined {@code formatLrcTime}.
 */
object LrcTimeFormatter {
    /**
     * Formats {@code millis} as {@code mm:ss.xxx}. Negative inputs become {@code 00:00.000}.
     */
    fun format(millis: Long): String {
        val safeTime = millis.coerceAtLeast(0L)
        val minutes = safeTime / 60_000L
        val seconds = (safeTime % 60_000L) / 1_000L
        val fraction = safeTime % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, fraction)
    }
}
