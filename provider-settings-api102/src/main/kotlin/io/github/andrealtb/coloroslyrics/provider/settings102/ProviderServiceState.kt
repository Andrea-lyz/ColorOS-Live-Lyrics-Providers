/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.settings102

import io.github.libxposed.service.XposedService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the currently connected Xposed service. A died service is dropped immediately and UI
 * listeners are notified so settings pages can revoke stale references and disable controls.
 */
object ProviderServiceState {

    @Volatile
    private var currentService: XposedService? = null

    private val listeners = CopyOnWriteArrayList<(XposedService?) -> Unit>()

    val service: XposedService?
        get() = currentService

    fun onServiceBind(service: XposedService) {
        currentService = service
        dispatch(service)
    }

    fun onServiceDied(service: XposedService) {
        if (currentService === service) {
            currentService = null
            dispatch(null)
        }
    }

    fun addListener(listener: (XposedService?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (XposedService?) -> Unit) {
        listeners.remove(listener)
    }

    private fun dispatch(service: XposedService?) {
        listeners.forEach { listener -> runCatching { listener(service) } }
    }
}
