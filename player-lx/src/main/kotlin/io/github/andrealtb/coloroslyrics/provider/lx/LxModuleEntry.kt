/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for LX Music / Walnut. The two host packages run in
 * separate processes; each accepted process bootstraps independently and carries its own
 * hostPackage through publication and replay ownership.
 */
class LxModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.LX

    override val diagnosticsComponent: String = "provider/lx"

    override val processPolicy: ProviderProcessPolicy =
        ScopeOnlyProcessPolicy(LxPlayerConstants.QUALIFIED_HOST_PACKAGES.toSet())

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> LxPlayerHooker(context).onHook() }
}
