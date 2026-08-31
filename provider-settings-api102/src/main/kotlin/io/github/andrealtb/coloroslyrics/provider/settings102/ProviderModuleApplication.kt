/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.settings102

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Shared Application for Provider module apps, referenced by each AndroidManifest
 * application android:name. Registers the libxposed service listener exactly once per process;
 * service state is held by [ProviderServiceState].
 */
open class ProviderModuleApplication : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        runCatching { XposedServiceHelper.registerListener(this) }
    }

    final override fun onServiceBind(service: XposedService) {
        ProviderServiceState.onServiceBind(service)
    }

    final override fun onServiceDied(service: XposedService) {
        ProviderServiceState.onServiceDied(service)
    }
}
