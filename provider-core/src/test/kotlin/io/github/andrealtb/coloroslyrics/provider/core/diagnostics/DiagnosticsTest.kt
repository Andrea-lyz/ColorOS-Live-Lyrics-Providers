/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.diagnostics

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {

    @Test
    fun testThrottlerThrottlesRepeatedKeysWithinWindow() {
        val throttler = DiagnosticThrottler(windowMillis = 1000L)
        val t0 = 10000L
        assertTrue(throttler.shouldLog("event-1", t0))
        assertFalse(throttler.shouldLog("event-1", t0 + 200))
        assertFalse(throttler.shouldLog("event-1", t0 + 999))
        assertTrue(throttler.shouldLog("event-1", t0 + 1000))
    }

    @Test
    fun testSensitiveFieldRedactorRedactsTokensAndPasswords() {
        val msg = "User token=abc123def456ghi789 and cookie=xyz_secret_val with password=my_secret_pass"
        val redacted = SensitiveFieldRedactor.redact(msg)
        assertFalse(redacted.contains("abc123def456ghi789"))
        assertFalse(redacted.contains("xyz_secret_val"))
        assertFalse(redacted.contains("my_secret_pass"))
        assertTrue(redacted.contains("<REDACTED_TOKEN>"))
        assertTrue(redacted.contains("<REDACTED_COOKIE>"))
        assertTrue(redacted.contains("<REDACTED_PWD>"))
    }
}
