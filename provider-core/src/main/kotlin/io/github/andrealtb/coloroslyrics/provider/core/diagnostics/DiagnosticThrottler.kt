/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.diagnostics

import java.util.concurrent.ConcurrentHashMap

class DiagnosticThrottler(
    private val windowMillis: Long = 5000L
) {
    private val lastLoggedTimes = ConcurrentHashMap<String, Long>()

    fun shouldLog(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val lastTime = lastLoggedTimes[key]
        if (lastTime == null || nowMillis - lastTime >= windowMillis) {
            lastLoggedTimes[key] = nowMillis
            return true
        }
        return false
    }

    fun clear() {
        lastLoggedTimes.clear()
    }
}
