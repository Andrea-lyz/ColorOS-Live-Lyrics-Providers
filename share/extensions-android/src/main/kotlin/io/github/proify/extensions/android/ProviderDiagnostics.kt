/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.android

import android.util.Log

/** Lightweight host-process diagnostics that stay silent unless the tag is DEBUG-enabled. */
object ProviderDiagnostics {
    fun isDebugEnabled(tag: String): Boolean =
        runCatching { Log.isLoggable(tag, Log.DEBUG) }.getOrDefault(false)

    inline fun debug(tag: String, message: () -> String) {
        if (!isDebugEnabled(tag)) return
        runCatching { Log.d(tag, message()) }
    }
}
