/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeMode

internal object KuWoDiagnostics {
    const val COMPONENT = "provider/kuwo"

    fun info(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        message: String? = null,
        mode: RuntimeMode? = null
    ) {
        StructuredDiagnostics.logInfo(event(area, event, process, reason, message, mode))
    }

    fun debug(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        message: String? = null,
        mode: RuntimeMode? = null
    ) {
        StructuredDiagnostics.logDebug(event(area, event, process, reason, message, mode))
    }

    fun warn(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        message: String? = null,
        mode: RuntimeMode? = null
    ) {
        StructuredDiagnostics.logWarning(event(area, event, process, reason, message, mode))
    }

    fun error(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        message: String? = null,
        throwable: Throwable? = null
    ) {
        StructuredDiagnostics.logError(
            event(area, event, process, reason, message, null),
            throwable = throwable
        )
    }

    private fun event(
        area: String,
        event: String,
        process: String?,
        reason: String?,
        message: String?,
        mode: RuntimeMode?
    ) = DiagnosticEvent(
        component = COMPONENT,
        area = area,
        event = event,
        mode = mode,
        process = process,
        reason = reason,
        message = message
    )
}
