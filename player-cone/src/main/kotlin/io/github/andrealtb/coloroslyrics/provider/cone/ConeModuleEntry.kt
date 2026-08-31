/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.cone

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for Cone. Covers both the standard and GP host packages;
 * per-host state stays separated because each package runs its own process and the entry is
 * bootstrapped once per accepted process.
 */
class ConeModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.CONE

    override val diagnosticsComponent: String = "provider/cone"

    override val processPolicy: ProviderProcessPolicy =
        ScopeOnlyProcessPolicy(ConePlayerConstants.QUALIFIED_HOST_PACKAGES.toSet())

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> ConePlayerHooker(context).onHook() }
}
