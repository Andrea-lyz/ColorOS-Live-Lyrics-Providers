/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugSettingsActivity
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId

class SaltDebugSettingsActivity : ProviderDebugSettingsActivity() {
    override val providerId: ProviderId = ProviderId.SALT
    override val providerDisplayName: String = "Salt Player 歌词 Provider"
    override val targetPackageDescription: String = "目标播放器：com.salt.music"
}
