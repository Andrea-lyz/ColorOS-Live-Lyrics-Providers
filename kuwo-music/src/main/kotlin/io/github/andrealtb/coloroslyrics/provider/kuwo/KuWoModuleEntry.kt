/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy
import io.github.andrealtb.coloroslyrics.provider.hook102.ScopeOnlyProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for KuWo. The fully qualified name of this class is the
 * only line allowed in META-INF/xposed/java_init.list.
 */
class KuWoModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.KUWO

    override val diagnosticsComponent: String = "provider/kuwo"

    override val processPolicy: ProviderProcessPolicy =
        ScopeOnlyProcessPolicy(setOf(KuWoPlayerConstants.PLAYER_PACKAGE))

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> KuWo(context).onHook() }
}
