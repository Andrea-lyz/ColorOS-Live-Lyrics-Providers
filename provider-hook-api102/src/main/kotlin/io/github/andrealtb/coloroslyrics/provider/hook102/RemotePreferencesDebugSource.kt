/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.hook102

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugSource
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.libxposed.api.XposedInterface

/**
 * Debug switch read side inside target player processes: the module's own read-only Remote
 * Preferences. Missing capability, read errors or absent groups all resolve to null, which
 * ProviderDebugConfig maps to a disabled:* reason without touching lyric hook installation.
 */
class RemotePreferencesDebugSource(
    private val module: XposedInterface
) : ProviderDebugSource, io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugRevisionSource {

    @Volatile private var revision = -1L
    override fun observedRevision(): Long = revision
    override fun read(provider: ProviderId): Boolean? {
        revision = -1L
        return runCatching {
            val prefs = module.getRemotePreferences(ProviderDebugConfig.prefsName(provider))
            val enabled = prefs.getBoolean(ProviderDebugConfig.KEY_DEBUG_ENABLED, false)
            revision = runCatching { prefs.getLong(ProviderDebugConfig.KEY_REVISION, -1L) }.getOrDefault(-1L)
            enabled
        }.getOrNull()
    }
}
