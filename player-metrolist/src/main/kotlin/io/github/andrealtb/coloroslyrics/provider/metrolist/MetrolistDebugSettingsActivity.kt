/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.settings102.ProviderDebugSettingsActivity

class MetrolistDebugSettingsActivity : ProviderDebugSettingsActivity() {
    override val providerId: ProviderId = ProviderId.METROLIST
    override val providerDisplayName: String = "Metrolist 歌词 Provider"
    override val targetPackageDescription: String =
        "目标播放器：com.metrolist.music"
}
