/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.scopeprobe

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.andrealtb.coloroslyrics.provider.settings102.ProviderServiceState
import io.github.libxposed.service.XposedService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** SDK leaf: all calls use the service delivered to THIS module App, never another module's id. */
internal class LibxposedScopeProbeClient(private val service: XposedService) : ScopeProbeClient {
    override fun frameworkInfo(): String =
        service.frameworkName.take(80) + " / " + service.frameworkVersion.take(80) +
            " / API " + service.apiVersion

    override fun readScope(): Set<String> = service.scope.toSet()

    override fun requestScope(packages: List<String>, callback: (ScopeProbeResult) -> Unit) {
        service.requestScope(packages, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                callback(ScopeProbeResult.Approved(approved.toSet()))
            }

            override fun onScopeRequestFailed(message: String) {
                // API 102 gives no typed reject / never-ask-again discriminator. Do not guess.
                callback(ScopeProbeResult.Failed)
            }
        })
    }
}

private class PreferencesAttemptStore(private val preferences: SharedPreferences) : ProbeAttemptStore {
    override fun wasAttempted(): Boolean = preferences.getBoolean("submitted", false)
    override fun recordAttempt(): Boolean = synchronized(submissionLock) {
        if (wasAttempted()) false else preferences.edit().putBoolean("submitted", true).commit()
    }

    private companion object {
        // Multiple foreground Activity instances must not both pass a stale in-memory guard.
        val submissionLock = Any()
    }
}

/**
 * Explicitly started/stopped worker owner, retained across configuration changes without an Activity.
 * It does not add a second XposedServiceHelper listener; it observes the existing Application owner.
 * Binder has no cancellation API: close detaches UI and drops late work, not the framework prompt.
 */
internal class ScopeProbeSession(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val foreground = AtomicBoolean(false)
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var started = false
    private var rawService: XposedService? = null // Worker-confined.
    private var observer: ((ScopeProbeSnapshot) -> Unit)? = null // Main-thread-confined.
    private val controller: ScopeProbeController = ScopeProbeController(
        allowedHosts = BuildConfig.ALLOWED_HOSTS.split(',').toSet(),
        store = PreferencesAttemptStore(context.applicationContext.getSharedPreferences(
            "scope_probe_attempt", Context.MODE_PRIVATE
        )),
        dispatch = { action -> submit(action) },
        afterTimeout = { action ->
            if (!closed.get()) {
                try {
                    worker.schedule({ if (!closed.get()) action() }, 60, TimeUnit.SECONDS)
                } catch (_: RejectedExecutionException) {
                    // Owner closed while a Binder call was finishing. Do not restart work.
                }
            }
        },
        onChanged = { state ->
            latest = state
            main.post { if (!closed.get()) observer?.invoke(latest) }
        },
        maySubmit = { foreground.get() && !closed.get() }
    )
    @Volatile private var latest: ScopeProbeSnapshot = controller.snapshot

    private val serviceListener: (XposedService?) -> Unit = {
        // Re-read the current source on the worker instead of replaying an out-of-order bind event.
        submit { connectLatest() }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed.get()) return
        started = true
        ProviderServiceState.addListener(serviceListener)
        submit { connectLatest() }
    }

    fun attach(observer: (ScopeProbeSnapshot) -> Unit) {
        foreground.set(true)
        this.observer = observer
        observer(latest)
    }

    fun detach() {
        foreground.set(false)
        observer = null
    }

    fun refresh() = submit { connectLatest() }

    fun request() = submit {
        connectLatest()
        controller.request()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        foreground.set(false)
        observer = null
        ProviderServiceState.removeListener(serviceListener)
        worker.shutdownNow()
    }

    private fun connectLatest() {
        val service = ProviderServiceState.service
        if (service !== rawService) {
            rawService = service
            controller.connect(service?.let(::LibxposedScopeProbeClient))
        } else {
            controller.refresh()
        }
    }

    private fun submit(action: () -> Unit) {
        if (closed.get()) return
        try {
            worker.execute { if (!closed.get()) action() }
        } catch (_: RejectedExecutionException) {
            // Lifecycle close wins over a queued callback or click.
        }
    }
}
