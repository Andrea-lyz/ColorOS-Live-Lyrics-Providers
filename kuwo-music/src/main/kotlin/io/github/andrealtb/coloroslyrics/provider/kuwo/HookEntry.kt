/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver

@InjectYukiHookWithXposed(modulePackageName = KuWoPlayerConstants.MODULE_PACKAGE)
class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            loadApp(KuWoPlayerConstants.PLAYER_PACKAGE) {
                RuntimeModeResolver.notifyXposedHookActive()
                loadHooker(KuWo())
            }
        }
    }

    override fun onInit() {
        if (ProviderDebugConfig.readXposedSwitch(KuWoPlayerConstants.MODULE_PACKAGE, ProviderId.KUWO)) {
            YukiHookAPI.configs {
                debugLog {
                    tag = "KuWoMusicProvider"
                }
            }
        }
    }
}
