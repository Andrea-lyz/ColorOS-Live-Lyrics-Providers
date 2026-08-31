/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.settings102.ProviderDebugSettingsActivity

class KuWoDebugSettingsActivity : ProviderDebugSettingsActivity() {
    override val providerId: ProviderId = ProviderId.KUWO
    override val providerDisplayName: String = "酷我音乐歌词 Provider"
    override val targetPackageDescription: String = "目标播放器：cn.kuwo.player"
}
