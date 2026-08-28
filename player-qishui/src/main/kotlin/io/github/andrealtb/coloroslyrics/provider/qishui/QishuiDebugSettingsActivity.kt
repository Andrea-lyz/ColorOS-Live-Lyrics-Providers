/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugSettingsActivity
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId

class QishuiDebugSettingsActivity : ProviderDebugSettingsActivity() {
    override val providerId: ProviderId = ProviderId.QISHUI
    override val providerDisplayName: String = "汽水音乐歌词 Provider"
    override val targetPackageDescription: String = "目标播放器：com.luna.music"
}
