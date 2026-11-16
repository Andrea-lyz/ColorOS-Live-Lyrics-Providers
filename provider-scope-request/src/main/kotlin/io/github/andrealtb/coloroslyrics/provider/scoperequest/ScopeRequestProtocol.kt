/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.scoperequest

import java.io.InputStream

/**
 * Management protocol v1 for asking THIS Provider to request its own LSPosed scope (5.0 plan §7.2,
 * §7.3). The caller (the Bridge store, or the Provider's own settings page) only names hosts; this
 * module decides which of them it may ask for, from its compiled `META-INF/xposed/scope.list`, and
 * the framework shows its own scope-request prompt. Nothing here carries lyrics or a service object.
 */
object ScopeRequestProtocol {
    const val VERSION = 1
    const val ACTION = "io.github.andrealtb.coloroslyrics.action.REQUEST_PROVIDER_SCOPE"

    const val EXTRA_PROTOCOL = "cll.scope.protocol"
    const val EXTRA_REQUEST_ID = "cll.scope.requestId"
    const val EXTRA_HOSTS = "cll.scope.hosts"

    const val RESULT_STATUS = "cll.scope.status"
    const val RESULT_REQUESTED = "cll.scope.requested"
    const val RESULT_GRANTED = "cll.scope.granted"

    /** The compiled scope list, packaged as a Java resource by the module convention. */
    const val SCOPE_LIST_RESOURCE = "META-INF/xposed/scope.list"

    private val REQUEST_ID = Regex("[A-Za-z0-9_-]{8,64}")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val NEVER = setOf("android", "system", "com.android.systemui")
    private const val MAX_HOSTS = 16

    fun parseScopeList(stream: InputStream): Set<String> =
        stream.bufferedReader().readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && PACKAGE.matches(it) && it !in NEVER }
            .toSortedSet()

    /**
     * Checks one request against the hosts this module may ask for. Hosts outside the compiled
     * list are an invalid request, not silently dropped: the caller asked for something we never
     * request.
     */
    fun validate(protocol: Int, requestId: String?, hosts: List<String>?, allowed: Set<String>): ScopeRequest? {
        if (protocol != VERSION || requestId == null || !REQUEST_ID.matches(requestId)) return null
        if (hosts == null || hosts.isEmpty() || hosts.size > MAX_HOSTS) return null
        val asked = hosts.toSortedSet()
        if (asked.size != hosts.size || !allowed.containsAll(asked)) return null
        return ScopeRequest(requestId, asked)
    }
}

data class ScopeRequest(val requestId: String, val hosts: Set<String>)

/** Final, caller-visible outcome. Only a scope readback proves a host is granted. */
enum class ScopeRequestStatus {
    /** Every requested host was already in scope; no prompt was shown. */
    ALREADY_GRANTED,
    /** Readback after the prompt shows every requested host. */
    GRANTED,
    /** Readback shows some, not all, requested hosts. */
    PARTIAL,
    /** The framework answered and the readback shows none of the missing hosts (rejected, or "never ask"). */
    NOT_GRANTED,
    /** No answer in time; the prompt may still be open. */
    PENDING,
    /** This module's LSPosed service never connected (module disabled, or framework not ready). */
    NO_SERVICE,
    /** The framework call itself failed, or scope could not be read. */
    FAILED,
    /** Bad protocol, id, hosts, or an unverified caller. */
    INVALID_REQUEST,
}

data class ScopeRequestOutcome(
    val status: ScopeRequestStatus,
    val requested: Set<String> = emptySet(),
    /** Requested hosts present in the final readback. */
    val granted: Set<String> = emptySet(),
)
