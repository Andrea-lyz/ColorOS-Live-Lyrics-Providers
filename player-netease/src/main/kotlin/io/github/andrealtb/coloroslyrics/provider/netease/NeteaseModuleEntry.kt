/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy

/**
 * v4.1 single libxposed API 102 entry. The four accepted package/process combinations keep
 * their existing [NeteaseRuntimeProfile]; every other process detaches before business hooks.
 */
class NeteaseModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.NETEASE

    override val diagnosticsComponent: String = "provider/netease"

    override val processPolicy: ProviderProcessPolicy = NeteaseProfileProcessPolicy

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> NeteasePlayerHooker(context).onHook() }
}

internal object NeteaseProfileProcessPolicy : ProviderProcessPolicy {

    override val packages: Set<String> = NeteasePlayerConstants.QUALIFIED_HOST_PACKAGES.toSet()

    override fun accepts(packageName: String, processName: String): Boolean =
        NeteaseRuntimeProfile.resolve(packageName, processName) != null

    override fun describe(packageName: String, processName: String): String =
        NeteaseRuntimeProfile.resolve(packageName, processName)?.name?.lowercase()
            ?: "rejected"
}
