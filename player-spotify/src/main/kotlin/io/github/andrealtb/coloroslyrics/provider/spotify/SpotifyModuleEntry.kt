/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookInstaller
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderModuleEntry
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderProcessPolicy

/** v4.1 single libxposed API 102 entry for Spotify's main playback process. */
class SpotifyModuleEntry : ProviderModuleEntry() {

    override val providerId: ProviderId = ProviderId.SPOTIFY

    override val diagnosticsComponent: String = SpotifyPlayerConstants.COMPONENT

    override val processPolicy: ProviderProcessPolicy = SpotifyMainProcessPolicy

    override fun createHookInstaller(): ProviderHookInstaller =
        ProviderHookInstaller { context -> SpotifyPlayerHooker(context).onHook() }
}

/** Entry-level adapter over the existing, unit-tested main-process rule. */
internal object SpotifyMainProcessPolicy : ProviderProcessPolicy {

    override val packages: Set<String> = SpotifyPlayerConstants.QUALIFIED_HOST_PACKAGES.toSet()

    override fun accepts(packageName: String, processName: String): Boolean =
        packageName in packages && SpotifyPlayerConstants.isPlaybackProcess(processName)

    override fun describe(packageName: String, processName: String): String = "main"
}
