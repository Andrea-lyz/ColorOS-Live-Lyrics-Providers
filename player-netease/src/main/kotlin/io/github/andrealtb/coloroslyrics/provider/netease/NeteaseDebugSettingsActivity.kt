/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.settings102.ProviderDebugSettingsActivity

class NeteaseDebugSettingsActivity : ProviderDebugSettingsActivity() {
    override val providerId: ProviderId = ProviderId.NETEASE
    override val providerDisplayName: String = "网易云 / 荣耀云音乐歌词 Provider"
    override val targetPackageDescription: String =
        "目标播放器：com.netease.cloudmusic（主进程 + 9.0.40 :play） / " +
            "com.hihonor.cloudmusic（主进程 + :play）"
}
