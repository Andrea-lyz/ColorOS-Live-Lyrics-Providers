/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDiagnosticsTest {

    @After
    fun resetProperties() {
        clearTag("krc-roma-mismatch")
        clearTag("krc-translate-mismatch")
        clearTag("krc-language-decode")
        clearTag("provider-diag-test")
    }

    @Test
    fun warningEnabledByDefault() {
        // No system property set — warning should still be emitted.
        assertTrue(ProviderDiagnostics.isWarningEnabled("provider-diag-test"))
    }

    @Test
    fun debugDisabledByDefault() {
        // No system property set — debug is silent unless explicitly enabled.
        assertFalse(ProviderDiagnostics.isDebugEnabled("provider-diag-test"))
    }

    @Test
    fun debugOptInViaPropertyTrue() {
        enableTag("provider-diag-test", "true")
        assertTrue(ProviderDiagnostics.isDebugEnabled("provider-diag-test"))
    }

    @Test
    fun debugOptInViaPropertyAll() {
        enableTag("provider-diag-test", "all")
        assertTrue(ProviderDiagnostics.isDebugEnabled("provider-diag-test"))
        assertTrue(ProviderDiagnostics.isWarningEnabled("provider-diag-test"))
    }

    @Test
    fun debugOptOutDisablesLevel() {
        enableTag("provider-diag-test", "warning")
        assertFalse(ProviderDiagnostics.isDebugEnabled("provider-diag-test"))
        assertTrue(ProviderDiagnostics.isWarningEnabled("provider-diag-test"))
    }

    @Test
    fun messageLambdaIsLazy() {
        // Count invocations; the lambda should only run when the level is enabled.
        var invocations = 0
        ProviderDiagnostics.debug("provider-diag-test-disabled") {
            invocations++
            "should not run"
        }
        assertEquals(0, invocations)
    }

    private fun enableTag(tag: String, value: String) {
        System.setProperty("proify.provider.diag.$tag", value)
    }

    private fun clearTag(tag: String) {
        System.clearProperty("proify.provider.diag.$tag")
    }
}
