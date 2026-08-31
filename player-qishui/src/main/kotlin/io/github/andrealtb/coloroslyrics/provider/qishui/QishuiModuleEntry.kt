/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy

/**
 * v4.1 single libxposed API 102 entry for QiShui. Only the main com.luna.music process is
 * hooked; other processes detach before any business hook.
 */
class QishuiModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.QISHUI

    override val diagnosticsComponent: String = QishuiPlayerConstants.COMPONENT

    override val processPolicy: ProviderProcessPolicy = QishuiMainProcessPolicy

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> QishuiPlayerHooker(context).onHook() }
}

/** Entry-level adapter over the existing [QishuiPlayerConstants.isPlaybackProcess] rule. */
internal object QishuiMainProcessPolicy : ProviderProcessPolicy {

    override val packages: Set<String> = QishuiPlayerConstants.QUALIFIED_HOST_PACKAGES.toSet()

    override fun accepts(packageName: String, processName: String): Boolean =
        packageName in packages && QishuiPlayerConstants.isPlaybackProcess(processName)

    override fun describe(packageName: String, processName: String): String = "main"
}
