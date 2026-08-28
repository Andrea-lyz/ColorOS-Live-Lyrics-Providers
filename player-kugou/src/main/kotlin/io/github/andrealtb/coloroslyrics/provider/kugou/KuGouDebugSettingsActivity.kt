/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugSettingsActivity
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId

class KuGouDebugSettingsActivity : ProviderDebugSettingsActivity() {
    override val providerId: ProviderId = ProviderId.KUGOU
    override val providerDisplayName: String = "酷狗音乐歌词 Provider"
    override val targetPackageDescription: String =
        "目标播放器：com.kugou.android、com.kugou.android.lite"
}
