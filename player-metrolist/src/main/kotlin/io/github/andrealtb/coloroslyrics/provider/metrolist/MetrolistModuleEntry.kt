/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for Metrolist (main process only).
 */
class MetrolistModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.METROLIST

    override val diagnosticsComponent: String = "provider/metrolist"

    override val processPolicy: ProviderProcessPolicy =
        ScopeOnlyProcessPolicy(MetrolistPlayerConstants.QUALIFIED_HOST_PACKAGES.toSet())

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> MetrolistPlayerHooker(context).onHook() }
}
