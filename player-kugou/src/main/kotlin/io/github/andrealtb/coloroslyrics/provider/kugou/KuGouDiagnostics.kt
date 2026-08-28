/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeMode

object KuGouDiagnostics {
    const val COMPONENT = "provider/kugou"

    fun info(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        generation: Long? = null,
        message: String? = null,
        mode: RuntimeMode? = null,
        payloadChars: Int? = null
    ) {
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = COMPONENT,
                area = area,
                event = event,
                mode = mode,
                process = process,
                generation = generation,
                reason = reason,
                payloadChars = payloadChars,
                message = message
            )
        )
    }

    fun debug(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        generation: Long? = null,
        message: String? = null,
        mode: RuntimeMode? = null,
        payloadChars: Int? = null
    ) {
        StructuredDiagnostics.logDebug(
            DiagnosticEvent(
                component = COMPONENT,
                area = area,
                event = event,
                mode = mode,
                process = process,
                generation = generation,
                reason = reason,
                payloadChars = payloadChars,
                message = message
            )
        )
    }

    fun warn(
        area: String,
        event: String,
        process: String? = null,
        reason: String? = null,
        generation: Long? = null,
        message: String? = null,
        mode: RuntimeMode? = null
    ) {
        StructuredDiagnostics.logWarning(
            DiagnosticEvent(
                component = COMPONENT,
                area = area,
                event = event,
                mode = mode,
                process = process,
                generation = generation,
                reason = reason,
                message = message
            )
        )
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
            DiagnosticEvent(
                component = COMPONENT,
                area = area,
                event = event,
                process = process,
                reason = reason,
                message = message
            ),
            throwable = throwable
        )
    }
}
