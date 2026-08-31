/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/** v4.1 single libxposed API 102 entry for Apple Music. */
class AppleModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.APPLE

    override val diagnosticsComponent: String = ApplePlayerConstants.COMPONENT

    override val processPolicy = ScopeOnlyProcessPolicy(
        ApplePlayerConstants.QUALIFIED_HOST_PACKAGES.toSet()
    )

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> ApplePlayerHooker(context).onHook() }
}
