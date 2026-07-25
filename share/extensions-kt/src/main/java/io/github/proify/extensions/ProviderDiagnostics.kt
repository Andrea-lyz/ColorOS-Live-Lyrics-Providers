/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions

/**
 * Pure-JVM diagnostics for shared modules (e.g. `share:krckit`) that cannot
 * pull in `android.util.Log`. Output goes to [System.err] so it stays out
 * of stdout pipes, and gating uses the system property
 * `proify.provider.diag.<tag>` (matches the Android-side
 * `io.github.proify.extensions.android.ProviderDiagnostics` semantics).
 *
 * This class is intentionally separate from the Android variant — Android
 * Provider code keeps the `Log`-based one so logcat output is unchanged.
 */
object ProviderDiagnostics {
    private const val PROPERTY_PREFIX = "proify.provider.diag."

    fun isDebugEnabled(tag: String): Boolean = isEnabled(tag, "debug")

    fun isWarningEnabled(tag: String): Boolean = isEnabled(tag, "warning")

    inline fun debug(tag: String, message: () -> String) {
        if (!isDebugEnabled(tag)) return
        emit("DEBUG", tag, message())
    }

    inline fun logWarning(tag: String, message: () -> String) {
        if (!isWarningEnabled(tag)) return
        emit("WARN", tag, message())
    }

    @PublishedApi
    internal fun isEnabled(tag: String, kind: String): Boolean {
        val raw = runCatching { System.getProperty(PROPERTY_PREFIX + tag) }
            .getOrNull()
            .orEmpty()
            .trim()
            .lowercase()
        if (raw.isEmpty()) {
            // Default-on warnings keep parity with the Android variant which
            // surfaces them unconditionally through `Log.w`. Debug stays off
            // unless explicitly enabled.
            return kind == "warning"
        }
        return raw == "true" || raw == "1" || raw == "yes" ||
            raw == "all" || raw == kind
    }

    @PublishedApi
    internal fun emit(level: String, tag: String, message: String) {
        runCatching {
            System.err.println("[$level/$tag] $message")
            System.err.flush()
        }
    }
}
