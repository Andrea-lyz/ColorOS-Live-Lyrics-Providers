/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.scoperequest

import java.util.concurrent.CancellationException

/** Blocking SDK seam over THIS module's own XposedService; called only on the controller's worker. */
interface ScopeClient {
    fun readScope(): Set<String>
    fun requestScope(packages: List<String>, callback: (approved: Set<String>?) -> Unit)
}

/**
 * One scope request, run once (production form of the M0 probe controller, plan §14.0). Reads the
 * current scope, asks the framework only for the missing requested hosts, and decides the outcome
 * from a fresh readback: the callback is evidence, the readback is the truth. Keeps existing scope;
 * never removes anything. [finish] is called exactly once.
 *
 * Every entry point must run on the same single worker; [dispatch] posts back onto it and
 * [schedule] runs an action there after a delay.
 */
class ScopeRequestController(
    private val request: ScopeRequest,
    private val dispatch: (() -> Unit) -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val finish: (ScopeRequestOutcome) -> Unit,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
) {
    private var done = false
    private var waiting = false
    private var client: ScopeClient? = null

    /** [client] is null when the module's service did not connect in time. */
    fun start(client: ScopeClient?) {
        if (done) return
        if (client == null) return complete(ScopeRequestStatus.NO_SERVICE, emptySet())
        this.client = client
        val before = read(client) ?: return complete(ScopeRequestStatus.FAILED, emptySet())
        val missing = request.hosts.filterNot { it in before }
        if (missing.isEmpty()) return complete(ScopeRequestStatus.ALREADY_GRANTED, request.hosts)
        waiting = true
        schedule(answerTimeoutMs) { if (waiting) settle(timedOut = true) }
        try {
            client.requestScope(missing) { approved -> dispatch { if (waiting) settle(timedOut = false, answered = approved) } }
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            waiting = false
            complete(ScopeRequestStatus.FAILED, before.intersect(request.hosts))
        }
    }

    private fun settle(timedOut: Boolean, answered: Set<String>? = null) {
        waiting = false
        val after = client?.let(::read) ?: return complete(ScopeRequestStatus.FAILED, emptySet())
        val granted = after.intersect(request.hosts)
        val status = when {
            granted.containsAll(request.hosts) -> ScopeRequestStatus.GRANTED
            timedOut -> ScopeRequestStatus.PENDING
            granted.isNotEmpty() -> ScopeRequestStatus.PARTIAL
            // API 102 has no typed reject / never-ask reason; an answer without the hosts is NOT_GRANTED.
            answered.isNullOrEmpty() -> ScopeRequestStatus.NOT_GRANTED
            // "Approved" yet nothing in the readback: the framework did not save it.
            else -> ScopeRequestStatus.FAILED
        }
        complete(status, granted)
    }

    private fun read(client: ScopeClient): Set<String>? = try {
        client.readScope()
    } catch (error: RuntimeException) {
        if (error is CancellationException) throw error
        null
    }

    private fun complete(status: ScopeRequestStatus, granted: Set<String>) {
        if (done) return
        done = true
        finish(ScopeRequestOutcome(status, request.hosts, granted.intersect(request.hosts)))
    }

    companion object {
        /** Time for the user to open the framework's prompt and answer it. */
        const val ANSWER_TIMEOUT_MS = 120_000L
        /** Time for this module's service to arrive after the App process starts. */
        const val SERVICE_TIMEOUT_MS = 10_000L
    }
}
