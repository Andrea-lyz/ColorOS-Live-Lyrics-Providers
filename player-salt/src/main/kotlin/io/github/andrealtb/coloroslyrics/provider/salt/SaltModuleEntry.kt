/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for Salt. The fully qualified name of this class is the
 * only line allowed in META-INF/xposed/java_init.list.
 */
class SaltModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.SALT

    override val diagnosticsComponent: String = "provider/salt"

    override val processPolicy: ProviderProcessPolicy =
        ScopeOnlyProcessPolicy(setOf(SaltPlayerConstants.SALT_PACKAGE))

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> SaltPlayerHooker(context).onHook() }
}
