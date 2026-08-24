/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.diagnostics

import java.util.concurrent.CopyOnWriteArrayList

object StructuredDiagnostics {

    private const val DEFAULT_TAG = "ColorOSLiveLyrics"

    private val sinks = CopyOnWriteArrayList<DiagnosticSink>().apply {
        add(LogcatSink())
    }

    private val throttler = DiagnosticThrottler(windowMillis = 3000L)

    var isDebugEnabled: Boolean = false

    fun addSink(sink: DiagnosticSink) {
        if (!sinks.contains(sink)) {
            sinks.add(sink)
        }
    }

    fun removeSink(sink: DiagnosticSink) {
        sinks.remove(sink)
    }

    fun logDebug(eventKey: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null, msg: () -> String) {
        if (!isDebugEnabled) return
        if (!throttler.shouldLog(eventKey)) return
        val redacted = SensitiveFieldRedactor.redact(msg())
        dispatch(DiagnosticSink.LEVEL_DEBUG, tag, "[$eventKey] $redacted", throwable)
    }

    fun logInfo(eventKey: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null, msg: () -> String) {
        if (!throttler.shouldLog(eventKey)) return
        val redacted = SensitiveFieldRedactor.redact(msg())
        dispatch(DiagnosticSink.LEVEL_INFO, tag, "[$eventKey] $redacted", throwable)
    }

    fun logWarning(eventKey: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null, msg: () -> String) {
        if (!throttler.shouldLog(eventKey)) return
        val redacted = SensitiveFieldRedactor.redact(msg())
        dispatch(DiagnosticSink.LEVEL_WARN, tag, "[$eventKey] $redacted", throwable)
    }

    fun logError(eventKey: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null, msg: () -> String) {
        if (!throttler.shouldLog(eventKey)) return
        val redacted = SensitiveFieldRedactor.redact(msg())
        dispatch(DiagnosticSink.LEVEL_ERROR, tag, "[$eventKey] $redacted", throwable)
    }

    private fun dispatch(level: Int, tag: String, message: String, throwable: Throwable?) {
        for (sink in sinks) {
            runCatching {
                sink.log(level, tag, message, throwable)
            }
        }
    }

    internal fun resetForTesting() {
        sinks.clear()
        sinks.add(LogcatSink())
        throttler.clear()
        isDebugEnabled = false
    }
}
