/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.scoperequest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeRequestControllerTest {
    private val salt = "com.salt.music"
    private val cone = "ink.trantor.coneplayer"
    private val coneGp = "ink.trantor.coneplayer.gp"

    /** Fake framework: [answer] decides what the user did, [scope] is what a readback returns. */
    private class Framework(var scope: MutableSet<String>) : ScopeClient {
        val asked = mutableListOf<List<String>>()
        var pending: ((Set<String>?) -> Unit)? = null
        var readFails = false
        override fun readScope(): Set<String> = if (readFails) throw IllegalStateException() else scope.toSet()
        override fun requestScope(packages: List<String>, callback: (Set<String>?) -> Unit) {
            asked += packages
            pending = callback
        }
    }

    private class Harness(hosts: Set<String>) {
        val outcomes = mutableListOf<ScopeRequestOutcome>()
        val timers = mutableListOf<() -> Unit>()
        val controller = ScopeRequestController(
            request = ScopeRequest("req-12345678", hosts),
            dispatch = { it() },
            schedule = { _, action -> timers += action },
            finish = { outcomes += it },
        )
    }

    @Test fun alreadyGrantedHostsShowNoPrompt() {
        val h = Harness(setOf(salt))
        val fw = Framework(mutableSetOf(salt, "other.app"))
        h.controller.start(fw)
        assertEquals(emptyList<List<String>>(), fw.asked)
        assertEquals(ScopeRequestOutcome(ScopeRequestStatus.ALREADY_GRANTED, setOf(salt), setOf(salt)), h.outcomes.single())
    }

    @Test fun asksOnlyForMissingHostsAndTrustsTheReadback() {
        val h = Harness(setOf(cone, coneGp))
        val fw = Framework(mutableSetOf(cone))
        h.controller.start(fw)
        assertEquals(listOf(listOf(coneGp)), fw.asked)
        assertTrue(h.outcomes.isEmpty())
        fw.scope += coneGp
        fw.pending!!(setOf(coneGp))
        assertEquals(ScopeRequestStatus.GRANTED, h.outcomes.single().status)
        assertEquals(setOf(cone, coneGp), h.outcomes.single().granted)
        // A late timeout after the answer changes nothing.
        h.timers.forEach { it() }
        assertEquals(1, h.outcomes.size)
    }

    @Test fun rejectionAndPartialAreReportedFromTheReadback() {
        val fw = Framework(mutableSetOf())
        val h = Harness(setOf(salt))
        h.controller.start(fw)
        fw.pending!!(null)
        assertEquals(ScopeRequestStatus.NOT_GRANTED, h.outcomes.single().status)

        val partial = Harness(setOf(cone, coneGp))
        val fw2 = Framework(mutableSetOf())
        partial.controller.start(fw2)
        fw2.scope += cone
        fw2.pending!!(setOf(cone, coneGp))
        assertEquals(ScopeRequestOutcome(ScopeRequestStatus.PARTIAL, setOf(cone, coneGp), setOf(cone)), partial.outcomes.single())
    }

    @Test fun approvedButNotSavedIsAFailureNotASuccess() {
        val h = Harness(setOf(salt))
        val fw = Framework(mutableSetOf())
        h.controller.start(fw)
        fw.pending!!(setOf(salt))
        assertEquals(ScopeRequestStatus.FAILED, h.outcomes.single().status)
    }

    @Test fun timeoutIsPendingAndALateAnswerIsIgnored() {
        val h = Harness(setOf(salt))
        val fw = Framework(mutableSetOf())
        h.controller.start(fw)
        h.timers.single()()
        assertEquals(ScopeRequestStatus.PENDING, h.outcomes.single().status)
        fw.scope += salt
        fw.pending!!(setOf(salt))
        assertEquals(1, h.outcomes.size)
    }

    @Test fun noServiceAndUnreadableScopeFailClosed() {
        val none = Harness(setOf(salt))
        none.controller.start(null)
        assertEquals(ScopeRequestStatus.NO_SERVICE, none.outcomes.single().status)
        val unreadable = Harness(setOf(salt))
        unreadable.controller.start(Framework(mutableSetOf()).apply { readFails = true })
        assertEquals(ScopeRequestStatus.FAILED, unreadable.outcomes.single().status)
    }

    @Test fun requestsAreValidatedAgainstTheCompiledList() {
        val allowed = ScopeRequestProtocol.parseScopeList(
            "$cone\n$coneGp\n\n# comment\ncom.android.systemui\nandroid\nnot a package\n".byteInputStream())
        assertEquals(setOf(cone, coneGp), allowed)
        val ok = ScopeRequestProtocol.validate(1, "req-12345678", listOf(coneGp, cone), allowed)
        assertEquals(ScopeRequest("req-12345678", setOf(cone, coneGp)), ok)
        assertNull(ScopeRequestProtocol.validate(2, "req-12345678", listOf(cone), allowed))
        assertNull(ScopeRequestProtocol.validate(1, "short", listOf(cone), allowed))
        assertNull(ScopeRequestProtocol.validate(1, "req-12345678", emptyList(), allowed))
        assertNull(ScopeRequestProtocol.validate(1, "req-12345678", listOf(cone, cone), allowed))
        // Outside the compiled list (another player, SystemUI): rejected, not trimmed.
        assertNull(ScopeRequestProtocol.validate(1, "req-12345678", listOf(cone, salt), allowed))
        assertNull(ScopeRequestProtocol.validate(1, "req-12345678", listOf("com.android.systemui"), allowed))
    }
}
