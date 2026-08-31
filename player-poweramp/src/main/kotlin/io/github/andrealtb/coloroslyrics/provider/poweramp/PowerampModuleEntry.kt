/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for Poweramp.
 */
class PowerampModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.POWERAMP

    override val diagnosticsComponent: String = "provider/poweramp"

    override val processPolicy: ProviderProcessPolicy =
        ScopeOnlyProcessPolicy(PowerampPlayerConstants.QUALIFIED_HOST_PACKAGES.toSet())

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> PowerampPlayerHooker(context).onHook() }
}
