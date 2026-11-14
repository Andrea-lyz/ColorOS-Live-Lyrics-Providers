/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.scopeprobe

import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeProbeControllerTest {
    private val host = "com.example.player"
    private val secondHost = "com.example.player.second"

    private class Store(var recorded: Boolean = false) : ProbeAttemptStore {
        var writable = true
        var onRecord: (() -> Unit)? = null
        override fun wasAttempted(): Boolean = recorded
        override fun recordAttempt(): Boolean {
            if (recorded || !writable) return false
            recorded = true
            onRecord?.invoke()
            return true
        }
    }

    private class Client(var scope: Set<String> = emptySet()) : ScopeProbeClient {
        var readError: RuntimeException? = null
        var submitError: RuntimeException? = null
        var reads = 0
        var onSubmit: (() -> Unit)? = null
        val requests = mutableListOf<List<String>>()
        val callbacks = mutableListOf<(ScopeProbeResult) -> Unit>()
        override fun frameworkInfo(): String = "test / API 102"
        override fun readScope(): Set<String> {
            reads++
            readError?.let { throw it }
            return scope
        }
        override fun requestScope(packages: List<String>, callback: (ScopeProbeResult) -> Unit) {
            onSubmit?.invoke()
            requests += packages.toList()
            submitError?.let { throw it }
            callbacks += callback
        }
    }

    private class Harness(hosts: Set<String>, val store: Store = Store()) {
        val timers = mutableListOf<() -> Unit>()
        var foreground = true
        val controller = ScopeProbeController(hosts, store, { it() }, { timers += it }, {}, { foreground })
        val state get() = controller.snapshot
    }

    @Test fun unavailableServiceIsUnknownAndCannotSubmit() {
        val h = Harness(setOf(host))
        h.controller.refresh()
        h.controller.request()
        assertFalse(h.state.connected)
        assertNull(h.state.scope)
        assertFalse(h.state.scopeConfirmed)
        assertFalse(h.store.recorded)
    }

    @Test fun bindAndRepeatedReadsNeverRequest() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        repeat(4) { h.controller.connect(client); h.controller.refresh() }
        assertTrue(h.state.canRequest)
        assertTrue(client.requests.isEmpty())
        assertFalse(h.store.recorded)
    }

    @Test fun alreadyGrantedScopeIsPreservedIncludingExtras() {
        val h = Harness(setOf(host))
        val client = Client(setOf(host, "com.example.existing"))
        h.controller.connect(client)
        h.controller.request()
        assertTrue(h.state.scopeConfirmed)
        assertEquals(client.scope, h.state.scope)
        assertTrue(client.requests.isEmpty())
        assertFalse(h.store.recorded)
    }

    @Test fun requestRereadsAndOnlySubmitsMissingCompiledHosts() {
        val h = Harness(setOf(host, secondHost))
        val client = Client()
        h.controller.connect(client)
        client.scope = setOf(host)
        client.onSubmit = { assertTrue(h.store.recorded, "guard must commit before SDK call") }
        h.controller.request()
        assertEquals(listOf(listOf(secondHost)), client.requests)
        assertEquals(ProbeRequestStatus.WAITING, h.state.requestStatus)
    }

    @Test fun scopeGrantedBetweenReadAndClickDoesNotPrompt() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        client.scope = setOf(host)
        h.controller.request()
        assertTrue(client.requests.isEmpty())
        assertTrue(h.state.scopeConfirmed)
    }

    @Test fun repeatedClicksCannotDuplicateOutstandingRequest() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        repeat(5) { h.controller.request() }
        assertEquals(1, client.requests.size)
        assertEquals(1, h.timers.size)
    }

    @Test fun approvedCallbackWithoutReadbackIsNotSuccess() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host)))
        assertEquals(ProbeRequestStatus.APPROVED_CALLBACK, h.state.requestStatus)
        assertEquals(setOf(host), h.state.callbackApprovedHosts)
        assertFalse(h.state.scopeConfirmed)
        assertFalse(h.state.canRequest)
    }

    @Test fun readbackConfirmsScopeButNotRunningLyrics() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.scope = setOf(host)
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host)))
        assertTrue(h.state.scopeConfirmed)
        assertEquals(ProbeRequestStatus.APPROVED_CALLBACK, h.state.requestStatus)
    }

    @Test fun partialReadbackDoesNotConfirmWholeRequestedSubset() {
        val h = Harness(setOf(host, secondHost))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.scope = setOf(host)
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host, "system", "com.android.systemui")))
        assertFalse(h.state.scopeConfirmed)
        assertEquals(setOf(host), h.state.callbackApprovedHosts)
        assertFalse(h.state.canRequest)
    }

    @Test fun failedCallbackRemainsGenericAndDoesNotRetry() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.callbacks.single()(ScopeProbeResult.Failed)
        h.controller.request()
        assertEquals(ProbeRequestStatus.FAILED_CALLBACK, h.state.requestStatus)
        assertFalse(h.state.scopeConfirmed)
        assertEquals(1, client.requests.size)
    }

    @Test fun readbackCanConfirmDespiteFailedCallback() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.scope = setOf(host)
        client.callbacks.single()(ScopeProbeResult.Failed)
        assertTrue(h.state.scopeConfirmed)
        assertEquals(ProbeRequestStatus.FAILED_CALLBACK, h.state.requestStatus)
    }

    @Test fun readFailureRevokesOldScopeEvidenceAndPreventsSubmission() {
        val h = Harness(setOf(host))
        val client = Client(setOf(host))
        h.controller.connect(client)
        client.readError = IllegalStateException("private details must not be copied")
        h.controller.request()
        assertNull(h.state.scope)
        assertFalse(h.state.scopeConfirmed)
        assertTrue(client.requests.isEmpty())
        assertFalse(h.state.events.any { "private details" in it })
    }

    @Test fun callbackReadFailureIsUnknownEvenWhenApproved() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.readError = IllegalStateException()
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host)))
        assertNull(h.state.scope)
        assertFalse(h.state.scopeConfirmed)
    }

    @Test fun failedDurableRecordPreventsSdkRequest() {
        val h = Harness(setOf(host), Store().apply { writable = false })
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        assertTrue(client.requests.isEmpty())
        assertFalse(h.state.attempted)
    }

    @Test fun restoredGuardPreventsManualOrLifecycleReplay() {
        val store = Store()
        val original = Harness(setOf(host), store)
        val client = Client()
        original.controller.connect(client)
        original.controller.request()
        val restored = Harness(setOf(host), store)
        restored.controller.connect(client)
        restored.controller.refresh()
        restored.controller.request()
        assertEquals(ProbeRequestStatus.RESTORED_UNKNOWN, restored.state.requestStatus)
        assertEquals(1, client.requests.size)
        assertFalse(restored.state.canRequest)
    }

    @Test fun timeoutReconcilesButDoesNotResubmitOrAcceptLateCallback() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        h.timers.single()()
        client.scope = setOf(host)
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host)))
        assertEquals(ProbeRequestStatus.TIMED_OUT, h.state.requestStatus)
        assertFalse(h.state.scopeConfirmed, "late callback must not update old readback")
        h.controller.refresh()
        assertTrue(h.state.scopeConfirmed)
        h.controller.request()
        assertEquals(1, client.requests.size)
    }

    @Test fun serviceDeathInvalidatesReadbackAndPendingCallback() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        h.controller.connect(null)
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host)))
        assertFalse(h.state.connected)
        assertNull(h.state.scope)
        assertEquals(ProbeRequestStatus.INTERRUPTED, h.state.requestStatus)
        assertFalse(h.state.scopeConfirmed)
    }

    @Test fun staleCallbackCannotOverwriteReconnectedService() {
        val h = Harness(setOf(host))
        val old = Client()
        h.controller.connect(old)
        h.controller.request()
        h.controller.connect(Client(setOf(host)))
        old.callbacks.single()(ScopeProbeResult.Failed)
        assertTrue(h.state.scopeConfirmed)
        assertEquals(ProbeRequestStatus.INTERRUPTED, h.state.requestStatus)
    }

    @Test fun duplicateCallbacksAndTimersDoNotReplaceFirstResult() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.controller.request()
        client.callbacks.single()(ScopeProbeResult.Failed)
        client.callbacks.single()(ScopeProbeResult.Approved(setOf(host)))
        h.timers.single()()
        assertEquals(ProbeRequestStatus.FAILED_CALLBACK, h.state.requestStatus)
    }

    @Test fun uncertainSubmitExceptionDoesNotAllowRetry() {
        val h = Harness(setOf(host))
        val client = Client().apply { submitError = IllegalStateException() }
        h.controller.connect(client)
        h.controller.request()
        h.controller.request()
        assertEquals(ProbeRequestStatus.SUBMIT_FAILED, h.state.requestStatus)
        assertEquals(1, client.requests.size)
        assertTrue(h.store.recorded)
    }

    @Test fun cancellationIsNotConvertedIntoOrdinaryFailure() {
        val h = Harness(setOf(host))
        val client = Client().apply { readError = CancellationException() }
        assertFailsWith<CancellationException> { h.controller.connect(client) }
    }

    @Test fun scopeSnapshotsDoNotAliasMutableSdkCollections() {
        val h = Harness(setOf(host))
        val mutable = mutableSetOf(host)
        h.controller.connect(Client(mutable))
        mutable.clear()
        assertTrue(h.state.scopeConfirmed)
    }

    @Test fun traceRetentionIsBounded() {
        val h = Harness(setOf(host))
        h.controller.connect(Client())
        repeat(100) { h.controller.refresh() }
        assertEquals(32, h.state.events.size)
    }

    @Test fun twoExistingOwnersCannotSubmitTheSamePersistedAttemptTwice() {
        val store = Store()
        val first = Harness(setOf(host), store)
        val second = Harness(setOf(host), store)
        val client = Client()
        first.controller.connect(client)
        second.controller.connect(client)
        first.controller.request()
        second.controller.request()
        assertEquals(1, client.requests.size)
        assertTrue(second.state.attempted)
        assertFalse(second.state.canRequest)
    }

    @Test fun unreadableRestoredGuardFailsClosed() {
        val store = object : ProbeAttemptStore {
            override fun wasAttempted(): Boolean = throw IllegalStateException()
            override fun recordAttempt(): Boolean = error("must not claim unreadable guard")
        }
        val controller = ScopeProbeController(setOf(host), store, { it() }, {}, {})
        val client = Client()
        controller.connect(client)
        controller.request()
        assertTrue(client.requests.isEmpty())
        assertEquals(ProbeRequestStatus.RESTORED_UNKNOWN, controller.snapshot.requestStatus)
    }

    @Test fun backgroundClickCannotSendOrConsumeAttempt() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.foreground = false
        h.controller.request()
        assertTrue(client.requests.isEmpty())
        assertFalse(h.store.recorded)
    }

    @Test fun leavingForegroundDuringDurableCommitDoesNotSubmit() {
        val h = Harness(setOf(host))
        val client = Client()
        h.controller.connect(client)
        h.store.onRecord = { h.foreground = false }
        h.controller.request()
        assertTrue(client.requests.isEmpty())
        assertTrue(h.store.recorded)
        assertTrue(h.state.attempted)
        h.foreground = true
        h.controller.request()
        assertTrue(client.requests.isEmpty())
    }

    @Test fun unsafeOrEmptyCompiledScopeIsRejected() {
        listOf(emptySet(), setOf("system"), setOf("android"), setOf("com.android.systemui"),
            setOf(host, "bad/host")).forEach { hosts ->
            assertFailsWith<IllegalArgumentException> { Harness(hosts) }
        }
    }
}
