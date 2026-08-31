/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for QQ Music. Only the :QQPlayerService process is
 * hooked; other processes detach before any business hook.
 */
class QqModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.QQ

    override val diagnosticsComponent: String = "provider/qq"

    override val processPolicy: ProviderProcessPolicy = QqServiceProcessPolicy

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> QqPlayerHooker(context).onHook() }
}

/** Entry-level adapter over the existing, unit-tested [QqProcessPolicy]. */
internal object QqServiceProcessPolicy : ProviderProcessPolicy {

    override val packages: Set<String> = setOf(QqPlayerConstants.HOST_PACKAGE)

    override fun accepts(packageName: String, processName: String): Boolean =
        QqProcessPolicy.shouldHook(packageName, processName)

    override fun describe(packageName: String, processName: String): String = "qqplayer-service"
}
