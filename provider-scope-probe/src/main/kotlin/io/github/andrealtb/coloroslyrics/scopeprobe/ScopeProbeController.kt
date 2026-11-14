/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.scopeprobe

import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException

/** Blocking SDK seam. Only the probe's owned worker may call it. */
internal interface ScopeProbeClient {
    fun frameworkInfo(): String
    fun readScope(): Set<String>
    fun requestScope(packages: List<String>, callback: (ScopeProbeResult) -> Unit)
}

internal sealed interface ScopeProbeResult {
    data class Approved(val packages: Set<String>) : ScopeProbeResult
    data object Failed : ScopeProbeResult
}

internal interface ProbeAttemptStore {
    fun wasAttempted(): Boolean
    /** Atomically claim once and durably commit BEFORE requestScope; false also covers an old claim. */
    fun recordAttempt(): Boolean
}

internal enum class ProbeRequestStatus {
    NOT_SENT, WAITING, APPROVED_CALLBACK, FAILED_CALLBACK, TIMED_OUT,
    INTERRUPTED, RESTORED_UNKNOWN, SUBMIT_FAILED
}

internal data class ScopeProbeSnapshot(
    val allowedHosts: Set<String>,
    val connected: Boolean = false,
    val framework: String = "unknown",
    val scope: Set<String>? = null,
    val attempted: Boolean = false,
    val requestStatus: ProbeRequestStatus = ProbeRequestStatus.NOT_SENT,
    val callbackApprovedHosts: Set<String> = emptySet(),
    val events: List<String> = emptyList()
) {
    val scopeConfirmed: Boolean get() = scope?.containsAll(allowedHosts) == true
    val canRequest: Boolean get() = connected && scope != null && !scopeConfirmed && !attempted
}

/**
 * Single-worker-confined UI state holder for ONE manual experiment. Lifecycle reads NEVER request
 * scope. A local persisted submission guard survives process death; this is not a production
 * transaction/recovery protocol. Callbacks are evidence only; readScope is authoritative.
 */
internal class ScopeProbeController(
    allowedHosts: Set<String>,
    private val store: ProbeAttemptStore,
    private val dispatch: (() -> Unit) -> Unit,
    private val afterTimeout: (() -> Unit) -> Unit,
    private val onChanged: (ScopeProbeSnapshot) -> Unit,
    private val maySubmit: () -> Boolean = { true }
) {
    private val hosts = allowedHosts.toSortedSet().toSet()
    private var client: ScopeProbeClient? = null
    private var epoch = 0L
    private var nextRequest = 0L
    private var pending: Long? = null
    private var eventNumber = 0L

    var snapshot: ScopeProbeSnapshot
        private set

    init {
        require(hosts.isNotEmpty() && hosts.all {
            it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) &&
                it !in setOf("android", "com.android.systemui")
        }) { "Probe hosts must be compiled player packages" }
        // An unreadable guard is uncertain, so fail closed instead of risking a repeated prompt.
        val attempted = try {
            store.wasAttempted()
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            true
        }
        snapshot = ScopeProbeSnapshot(
            allowedHosts = hosts,
            attempted = attempted,
            requestStatus = if (attempted) ProbeRequestStatus.RESTORED_UNKNOWN
                else ProbeRequestStatus.NOT_SENT
        )
    }

    fun connect(newClient: ScopeProbeClient?) {
        if (client === newClient) {
            refresh()
            return
        }
        epoch++
        client = newClient
        val interrupted = pending != null
        pending = null
        snapshot = snapshot.copy(
            connected = newClient != null,
            scope = null,
            framework = "unknown",
            requestStatus = if (interrupted) ProbeRequestStatus.INTERRUPTED else snapshot.requestStatus
        )
        record(if (newClient == null) "SERVICE_UNAVAILABLE" else "SERVICE_BOUND")
        if (newClient != null) {
            try {
                snapshot = snapshot.copy(framework = newClient.frameworkInfo())
            } catch (error: RuntimeException) {
                failure("FRAMEWORK_INFO_FAILED", error)
            }
            refresh()
        }
    }

    fun refresh() {
        val current = client
        if (current == null) {
            snapshot = snapshot.copy(scope = null)
            record("SERVICE_UNAVAILABLE")
            return
        }
        try {
            snapshot = snapshot.copy(scope = current.readScope().toSet())
            record("SCOPE_READ_OK")
        } catch (error: RuntimeException) {
            snapshot = snapshot.copy(scope = null)
            failure("SCOPE_READ_FAILED", error)
        }
    }

    /** Only a foreground button click reaches this entry; no lifecycle or service callback does. */
    fun request() {
        if (!maySubmit()) {
            record("REQUEST_BLOCKED_NOT_FOREGROUND")
            return
        }
        if (snapshot.attempted) {
            record("REQUEST_BLOCKED_RECORDED_ATTEMPT")
            return
        }
        val current = client ?: run {
            record("REQUEST_BLOCKED_NO_SERVICE")
            return
        }
        refresh() // Do not act on an old readback or request an already granted host.
        val scope = snapshot.scope ?: return
        if (!maySubmit()) {
            record("REQUEST_BLOCKED_NOT_FOREGROUND")
            return
        }
        val missing = hosts.filterNot { it in scope }
        if (missing.isEmpty()) {
            record("REQUEST_SKIPPED_ALREADY_GRANTED")
            return
        }
        val saved = try {
            store.recordAttempt()
        } catch (error: RuntimeException) {
            failure("ATTEMPT_RECORD_FAILED", error)
            false
        }
        if (!saved) {
            val alreadyRecorded = try {
                store.wasAttempted()
            } catch (error: RuntimeException) {
                if (error is CancellationException) throw error
                true
            }
            if (alreadyRecorded) {
                snapshot = snapshot.copy(attempted = true, requestStatus = ProbeRequestStatus.RESTORED_UNKNOWN)
            }
            record("REQUEST_BLOCKED_RECORDED_OR_RECORD_FAILED")
            return
        }
        if (!maySubmit()) {
            snapshot = snapshot.copy(attempted = true, requestStatus = ProbeRequestStatus.RESTORED_UNKNOWN)
            record("REQUEST_ABORTED_NOT_FOREGROUND")
            return
        }
        val requestEpoch = epoch
        val requestId = ++nextRequest
        pending = requestId
        snapshot = snapshot.copy(attempted = true, requestStatus = ProbeRequestStatus.WAITING)
        record("REQUEST_SUBMITTING")
        val reference = WeakReference(this)
        afterTimeout { reference.get()?.timeout(requestEpoch, requestId) }
        try {
            current.requestScope(missing) { result ->
                // The SDK can retain callbacks indefinitely; retain neither this controller nor UI.
                reference.get()?.let { owner ->
                    owner.dispatch { owner.complete(requestEpoch, requestId, result) }
                }
            }
        } catch (error: RuntimeException) {
            if (pending == requestId) {
                pending = null
                snapshot = snapshot.copy(requestStatus = ProbeRequestStatus.SUBMIT_FAILED)
                failure("REQUEST_SUBMIT_FAILED", error)
                refresh()
            } else if (error is CancellationException) {
                throw error
            }
        }
    }

    private fun complete(requestEpoch: Long, requestId: Long, result: ScopeProbeResult) {
        if (requestEpoch != epoch || pending != requestId) {
            record("LATE_OR_DUPLICATE_CALLBACK_IGNORED")
            return
        }
        pending = null
        snapshot = when (result) {
            is ScopeProbeResult.Approved -> snapshot.copy(
                requestStatus = ProbeRequestStatus.APPROVED_CALLBACK,
                callbackApprovedHosts = result.packages.intersect(hosts)
            )
            ScopeProbeResult.Failed -> snapshot.copy(requestStatus = ProbeRequestStatus.FAILED_CALLBACK)
        }
        record("CALLBACK_" + snapshot.requestStatus.name)
        // A failed callback does not prove rejection; approved does not prove the scope was saved.
        refresh()
    }

    private fun timeout(requestEpoch: Long, requestId: Long) {
        if (requestEpoch != epoch || pending != requestId) return
        pending = null
        snapshot = snapshot.copy(requestStatus = ProbeRequestStatus.TIMED_OUT)
        record("CALLBACK_TIMEOUT_NO_RETRY")
        refresh()
    }

    private fun failure(event: String, error: RuntimeException) {
        if (error is CancellationException) throw error
        // Exception text/framework failure messages can contain sensitive details. Keep only type.
        record(event + ":" + error.javaClass.simpleName)
    }

    private fun record(event: String) {
        snapshot = snapshot.copy(events = (snapshot.events + "${++eventNumber}: $event").takeLast(32))
        onChanged(snapshot)
    }
}
