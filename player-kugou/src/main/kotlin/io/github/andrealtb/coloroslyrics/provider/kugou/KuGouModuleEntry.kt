/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for KuGou. Both hosts hook only their
 * `:support` / `.support` processes; other processes detach before any business hook.
 */
class KuGouModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.KUGOU

    override val diagnosticsComponent: String = "provider/kugou"

    override val processPolicy: ProviderProcessPolicy = KuGouSupportProcessPolicy

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> KuGouPlayerHooker(context).onHook() }
}

/** Entry-level adapter over the existing, unit-tested [KuGouProcessPolicy]. */
internal object KuGouSupportProcessPolicy : ProviderProcessPolicy {

    override val packages: Set<String> = KuGouPlayerConstants.QUALIFIED_HOST_PACKAGES.toSet()

    override fun accepts(packageName: String, processName: String): Boolean =
        KuGouProcessPolicy.shouldHook(packageName, processName)

    override fun describe(packageName: String, processName: String): String = "support-process"
}
